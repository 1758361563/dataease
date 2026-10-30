package io.dataease.company.bi.dataupdate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;

@Component
@ConditionalOnProperty(name="company.bi.auth.enabled",havingValue="true",matchIfMissing=true)
public class UpdateDdlService {
    public record Request(String operationId,String datasourceId,String table,String sql) { }
    private final DataSource metadata;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final UpdateConnections connections;
    private final UpdateInspection inspection;
    @org.springframework.beans.factory.annotation.Autowired
    public UpdateDdlService(DataSource metadata,UpdateConnections connections,UpdateInspection inspection) {
        this.metadata=metadata;this.connections=connections;this.inspection=inspection;
        db=new JdbcTemplate(metadata);tx=new TransactionTemplate(new DataSourceTransactionManager(metadata));
    }
    UpdateDdlService(DataSource metadata,UpdateConnections connections,UpdateInspection inspection,JdbcTemplate db,TransactionTemplate tx) {
        this.metadata=metadata;this.connections=connections;this.inspection=inspection;this.db=db;this.tx=tx;
    }
    private String key(Request request) { return UpdateStore.hash(UpdateStore.json(List.of(request.datasourceId(),request.table()))); }
    // Version-3 owner UUIDs cannot collide with the version-4 IDs allocated to group batches.
    private String claimOwner(String operationId) {return UUID.nameUUIDFromBytes(("data-update-ddl:"+operationId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
    public Map<String,Object> check(Request request) throws Exception {
        try(var handle=connections.open(request.datasourceId())) {
            require(UpdateConnections.Dialect.of(handle.connection())==UpdateConnections.Dialect.MYSQL,"受管建表入口当前支持 MySQL；其他数据源请选择已有表");
            String schema=connections.schema(request.datasourceId()); if(schema.isEmpty()) schema=handle.connection().getCatalog();
            require(schema!=null&&!schema.isBlank(),"请在目标数据源中指定数据库");
            var statements=UpdateDdl.validate(request.sql(),schema,request.table());
            boolean exists;
            try(PreparedStatement ps=handle.connection().prepareStatement("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
                ps.setQueryTimeout(10);ps.setString(1,schema);ps.setString(2,request.table());
                try(ResultSet rs=ps.executeQuery()) { rs.next(); exists=rs.getInt(1)>0; }
            }
            return Map.of("schema",schema,"statements",statements,"exists",exists,"sqlHash",UpdateStore.hash(request.sql()));
        }
    }
    public Map<String,Object> get(String id) {
        List<Map<String,Object>> rows=db.queryForList("SELECT id,datasource_id,table_name,status,completed_steps,structure_json,message,created_at,updated_at FROM company_data_update_ddl WHERE id=?",id);
        require(!rows.isEmpty(),"建表操作不存在");return rows.getFirst();
    }
    public Map<String,Object> create(Request request,Runnable audit) throws Exception {
        require(request.operationId()!=null&&request.operationId().matches("[a-fA-F0-9-]{36}"),"缺少有效的操作 ID");
        String targetKey=key(request);
        var inFlight=db.queryForList("SELECT sql_hash,target_key FROM company_data_update_ddl WHERE id=?",request.operationId());
        if(!inFlight.isEmpty()) {
            require(UpdateStore.hash(request.sql()).equals(inFlight.getFirst().get("sql_hash"))&&targetKey.equals(inFlight.getFirst().get("target_key")),"操作 ID 已用于其他建表请求");
            return get(request.operationId());
        }
        try(UpdateLock lock=UpdateLock.acquire(metadata,targetKey)) {
            require(lock!=null,"目标表正在执行更新或建表操作");
            var previous=db.queryForList("SELECT sql_hash,target_key FROM company_data_update_ddl WHERE id=?",request.operationId());
            if(!previous.isEmpty()) {
                require(UpdateStore.hash(request.sql()).equals(previous.getFirst().get("sql_hash"))&&targetKey.equals(previous.getFirst().get("target_key")),"操作 ID 已用于其他建表请求");
                return get(request.operationId());
            }
            Map<String,Object> checked=check(request);require(!(boolean)checked.get("exists"),"目标表已存在，请查看结构并使用已有表");
            tx.executeWithoutResult(status->{
                require(db.queryForObject("SELECT COUNT(*) FROM company_data_update_claim WHERE target_key=?",Integer.class,targetKey)==0,"目标表被任务批次或待核查的建表操作占用");
                db.update("INSERT INTO company_data_update_claim VALUES (?,?,UTC_TIMESTAMP(3))",targetKey,claimOwner(request.operationId()));
                db.update("INSERT INTO company_data_update_ddl(id,target_key,datasource_id,table_name,sql_hash,sql_text,status,created_at,updated_at) VALUES (?,?,?,?,?,?,'RUNNING',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                        request.operationId(),targetKey,request.datasourceId(),request.table(),UpdateStore.hash(request.sql()),request.sql());audit.run();
            });
            int completed=0;
            try(var handle=connections.open(request.datasourceId())) {
                for(String statement:UpdateDdl.validate(request.sql(),(String)checked.get("schema"),request.table())) {
                    require(lock.alive(),"建表执行锁已丢失，请核查目标对象");
                    try(Statement command=handle.connection().createStatement()) {command.setQueryTimeout(30);command.execute(statement);}
                    completed++;db.update("UPDATE company_data_update_ddl SET completed_steps=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",completed,request.operationId());
                }
                var structure=inspection.structure(request.datasourceId(),request.table());
                tx.executeWithoutResult(status->{
                    db.update("UPDATE company_data_update_ddl SET status='SUCCESS',structure_json=?,message='建表与结构回读完成',updated_at=UTC_TIMESTAMP(3) WHERE id=?",UpdateStore.json(structure),request.operationId());
                    db.update("DELETE FROM company_data_update_claim WHERE target_key=? AND owner_id=?",targetKey,claimOwner(request.operationId()));
                });
            } catch(Exception e) {
                db.update("UPDATE company_data_update_ddl SET status='NEEDS_REVIEW',message=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
                        "已确认完成 "+completed+" 步；"+UpdateRuntime.error(e)+"。请读取实际结构并核查，操作不会自动重放。",request.operationId());
            }
            return get(request.operationId());
        }
    }
    public Map<String,Object> reconcile(String id,Runnable audit) throws Exception {
        var operation=get(id);String datasource=(String)operation.get("datasource_id"),table=(String)operation.get("table_name");
        require(Set.of("RUNNING","NEEDS_REVIEW").contains(operation.get("status")),"该建表操作已经完成核查");
        String targetKey=UpdateStore.hash(UpdateStore.json(List.of(datasource,table)));
        try(UpdateLock lock=UpdateLock.acquire(metadata,targetKey)) {
            require(lock!=null,"建表操作仍在执行");
            require(Set.of("RUNNING","NEEDS_REVIEW").contains(get(id).get("status")),"建表状态已变化，请刷新记录");
            Object structure=null;
            try { structure=inspection.structure(datasource,table); } catch(UpdateDefinition.ValidationException ignored) { }
            String value=structure==null?null:UpdateStore.json(structure);
            tx.executeWithoutResult(status->{
                db.update("UPDATE company_data_update_ddl SET status='REVIEWED',structure_json=?,message='用户核查后解除占用；请按实际结构继续配置或补充索引',updated_at=UTC_TIMESTAMP(3) WHERE id=?",value,id);
                audit.run();db.update("DELETE FROM company_data_update_claim WHERE target_key=? AND owner_id=?",targetKey,claimOwner(id));
            });return get(id);
        }
    }
    public List<Map<String,Object>> list(String datasource) {return db.queryForList("SELECT id,table_name,status,completed_steps,message,created_at FROM company_data_update_ddl WHERE datasource_id=? ORDER BY created_at DESC LIMIT 50",datasource);}
}
