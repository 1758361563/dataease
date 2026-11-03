package io.dataease.company.bi.dataupdate;

import io.dataease.company.bi.admin.CompanyAdminGuard;
import io.dataease.company.bi.admin.CompanyAdminAuditContext;
import io.dataease.company.bi.audit.CompanyAuditRecorder;
import io.dataease.company.bi.audit.CompanyAuditActionType;
import io.dataease.company.bi.permission.CompanyCapability;
import io.dataease.utils.IDUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;

@RestController
@RequestMapping("/company/etl/data-update/sheets")
@ConditionalOnProperty(name="company.bi.auth.enabled",havingValue="true",matchIfMissing=true)
public class SheetController {
    private final CompanyAdminGuard guard;
    private final CompanyAuditRecorder audit;
    private final UpdateStore store;
    private final UpdateRuntime runtime;
    private final UpdateConnections connections;
    private final UpdateInspection inspection;
    private final SheetReader reader;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final DataSource metadata;
    private final Map<String,Ticket> tickets=new ConcurrentHashMap<>();
    private record Ticket(long user,String signature,String fingerprint,long expires) { }
    public SheetController(CompanyAdminGuard guard,CompanyAuditRecorder audit,UpdateStore store,UpdateRuntime runtime,
            UpdateConnections connections,UpdateInspection inspection,SheetReader reader,DataSource metadata) {
        this.guard=guard;this.audit=audit;this.store=store;this.runtime=runtime;this.connections=connections;this.inspection=inspection;this.reader=reader;
        this.metadata=metadata;db=new JdbcTemplate(metadata);tx=new TransactionTemplate(new DataSourceTransactionManager(metadata));
    }
    private long admin(){return guard.require(CompanyCapability.ETL_ACCESS);}
    public record Location(String connectionKey,String url) { }
    @GetMapping("/connections") public List<Map<String,Object>> connections() {
        admin(); return db.query("SELECT connection_key,config_json FROM company_integration_connection WHERE provider='FEISHU'",(rs,n)-> {
            try {
                var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(rs.getString(2));
                return Map.<String,Object>of("key",rs.getString(1),"name",json.path("appId").asText("飞书连接"),"enabled",json.path("runtimeEnabled").asBoolean());
            } catch(Exception e){throw new IllegalStateException("应用连接配置无效");}
        });
    }
    @PostMapping("/worksheets") public Map<String,Object> worksheets(@RequestBody Location request) {
        admin(); require(request.connectionKey()!=null && request.url()!=null,"请选择连接并填写表格地址");
        return reader.list(request.connectionKey(),request.url());
    }
    @PostMapping("/sample") public Map<String,Object> sample(@RequestBody SheetSource source) {
        admin(); source.validate(false); var headers=reader.headers(source);
        var page=reader.read(source,source.dataStartRow(),Math.min(source.dataStartRow()+14,source.dataEndRow()==null?1_000_000:source.dataEndRow()));
        return Map.of("headers",headers,"rows",bounded(page.rows()),"sheetRows",page.sheetRows(),"sampled",true);
    }
    private List<List<Object>> bounded(List<List<Object>> rows) {
        List<List<Object>> result=new ArrayList<>(); int bytes=0;
        for(var row:rows) {
            List<Object> cells=new ArrayList<>();
            for(Object cell:row) { String s=cell==null?null:cell.toString(); if(s!=null && s.length()>256)s=s.substring(0,256)+"…"; cells.add(s); }
            bytes+=UpdateStore.json(cells).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(bytes>128*1024)break; result.add(cells);
        } return result;
    }
    private UpdateDefinition normalize(UpdateDefinition task) {
        require(task.mode()==UpdateDefinition.Mode.FEISHU && task.sheet()!=null,"请选择飞书任务");
        require(db.queryForObject("SELECT COUNT(*) FROM core_datasource WHERE id=? AND type='mysql'",Integer.class,task.datasourceId())==1,"请选择 MySQL 目标数据源");
        String schema=connections.schema(task.datasourceId());
        require(schema!=null && !schema.isBlank(),"请在目标数据源中配置明确的数据库名称后接入飞书表格");
        return task.normalized(task.id(),task.version(),runtime.owner()).withSchema(schema);
    }
    private String signature(UpdateDefinition task) {
        return UpdateStore.hash(UpdateStore.json(List.of(task.datasourceId(),task.schema(),task.table(),task.sheet())));
    }
    @PostMapping("/preview") public Map<String,Object> preview(@RequestBody UpdateDefinition incoming)throws Exception {
        long user=admin(); var task=normalize(incoming);task.sheet().validate(true);
        var target=inspection.structure(task.datasourceId(),task.table());
        var headers=reader.headers(task.sheet());SheetValues.mapping(task.sheet(),headers,target);
        var page=reader.read(task.sheet(),task.sheet().dataStartRow(),Math.min(task.sheet().dataStartRow()+14,task.sheet().dataEndRow()==null?1_000_000:task.sheet().dataEndRow()));
        List<String> errors=new ArrayList<>();
        for(int r=0;r<page.rows().size();r++) {
            var row=page.rows().get(r);if(row.stream().allMatch(v->v==null || v.toString().isEmpty()))continue;
            for(var field:task.sheet().fields()) {
                int index=headers.indexOf(field.source());var column=target.columns().stream().filter(c->c.name().equals(field.target())).findFirst().orElseThrow();
                try{SheetValues.convert(index<row.size()?row.get(index):null,field,column,task.sheet().nullValues(),task.sheet().dataStartRow()+r);}
                catch(IllegalArgumentException e){if(errors.size()<30)errors.add(e.getMessage());}
            }
        }
        String token="";
        if(errors.isEmpty()) {
            tickets.entrySet().removeIf(entry->entry.getValue().expires()<System.currentTimeMillis());
            require(tickets.size()<256,"预览请求较多，请稍后重试");token=UUID.randomUUID().toString();
            tickets.put(token,new Ticket(user,signature(task),target.fingerprint(),System.currentTimeMillis()+900_000));
        }
        return Map.of("headers",headers,"rows",bounded(page.rows()),"target",target,"errors",errors,"token",token,"sampled",true,"sheetRows",page.sheetRows());
    }
    public record Save(UpdateDefinition definition,String previewToken,boolean takeoverConfirmed) { }
    @PostMapping public UpdateDefinition save(@RequestBody Save body,HttpServletRequest request)throws Exception {
        long user=admin();var task=normalize(body.definition());task.validate();
        require(!task.enabled() || runtime.owner()!=null,"启用调度前请配置实例标识");
        Ticket ticket=body.previewToken()==null?null:tickets.get(body.previewToken());
        require(ticket!=null && ticket.user()==user && ticket.expires()>System.currentTimeMillis() && ticket.signature().equals(signature(task)),"配置已改变或预览已过期，请重新预览");
        require(ticket.fingerprint().equals(task.structureFingerprint()) && ticket.fingerprint().equals(inspection.structure(task.datasourceId(),task.table()).fingerprint()),"目标结构已变化，请重新预览");
        var saved=tx.execute(status->{
            db.update("INSERT IGNORE INTO company_data_update_guard(id) VALUES (1)");
            db.queryForObject("SELECT id FROM company_data_update_guard WHERE id=1 FOR UPDATE",Integer.class);
            String sourceId=task.sheet().sourceId();boolean existing=sourceId!=null;
            if(task.id()!=null) {
                var previous=store.get(task.id());
                require(previous.mode()==UpdateDefinition.Mode.FEISHU && previous.sheet()!=null && Objects.equals(previous.sheet().sourceId(),sourceId),"已保存任务不能更换来源 ID");
            }
            require(db.queryForObject("SELECT COUNT(*) FROM company_feishu_source WHERE source_name=? AND id<>?",Integer.class,task.sheet().name(),sourceId==null?"0":sourceId)==0,"来源名称已存在，请选择接管或使用其他名称");
            if(existing) {
                var rows=db.queryForList("SELECT * FROM company_feishu_source WHERE id=? FOR UPDATE",sourceId);
                require(rows.size()==1,"历史来源不存在");var old=rows.getFirst();
                String linked=Objects.toString(old.get("managed_task_id"),"");
                require(linked.isEmpty()?body.takeoverConfirmed() && task.id()==null:linked.equals(task.id()),"接管前请停止旧调度并确认在途同步已结束");
                require(Objects.toString(old.get("target_database"),"").equals(task.schema()) && Objects.toString(old.get("target_table"),"").equals(task.table()),"已有来源的目标库表不能改变");
                if(linked.isEmpty())require(((Number)old.get("enabled")).intValue()==0,"请先停用旧来源调度，再执行接管");
            } else sourceId=Long.toString(IDUtils.snowID());
            require(task.id()==null || store.get(task.id()).mode()==UpdateDefinition.Mode.FEISHU,"不能将 SQL 任务替换为飞书任务");
            String id=sourceId; var source=task.sheet().withId(id);
            var result=store.save(task.withSheet(source),runtime.owner(),()->audit.record(CompanyAdminAuditContext.from(request,user),CompanyAuditActionType.DATA_UPDATE_SAVE,"FEISHU_SOURCE",null,null,UpdateStore.json(Map.of("sourceId",id,"table",task.table(),"takeover",body.takeoverConfirmed()))));
            long now=System.currentTimeMillis();
            if(!existing) db.update("INSERT INTO company_feishu_source(id,source_name,spreadsheet_url,sheet_id,sheet_name,header_row,data_start_row,target_database,target_table,enabled,schema_json,created_at,created_by,updated_at) VALUES (?,?,?,?,?,?,?,?,?,0,?,?,?,?)",
                    id,source.name(),source.url(),source.sheetId(),source.sheetName(),source.headerRow(),source.dataStartRow(),task.schema(),task.table(),UpdateStore.json(source.fields()),now,user,now);
            db.update("UPDATE company_feishu_source SET source_name=?,spreadsheet_url=?,sheet_id=?,sheet_name=?,header_row=?,data_start_row=?,data_end_row=?,column_range=?,managed_task_id=?,target_datasource_id=?,source_json=?,schema_json=?,enabled=0,schedule_json=NULL,updated_at=?,updated_by=? WHERE id=?",
                    source.name(),source.url(),source.sheetId(),source.sheetName(),source.headerRow(),source.dataStartRow(),source.dataEndRow(),source.columnRange(),result.id(),task.datasourceId(),UpdateStore.json(source),UpdateStore.json(source.fields()),now,user,id);
            return result;
        });
        tickets.remove(body.previewToken());return saved;
    }
    @GetMapping("/legacy") public List<Map<String,Object>> legacy() {
        admin();return db.query("SELECT id,source_name,spreadsheet_url,sheet_id,sheet_name,header_row,data_start_row,data_end_row,column_range,target_database,target_table,enabled,schema_json,schedule_json,latest_success_at FROM company_feishu_source WHERE managed_task_id IS NULL ORDER BY source_name",(rs,n)->{
            Map<String,Object> row=new LinkedHashMap<>();
            for(int i=1;i<=rs.getMetaData().getColumnCount();i++)row.put(rs.getMetaData().getColumnLabel(i),rs.getObject(i));
            row.put("id",rs.getString("id"));return row;
        });
    }
    @PostMapping("/runs/{run}/reconcile") public void reconcile(@PathVariable String run,HttpServletRequest request)throws Exception {
        long user=admin();
        var rows=db.queryForList("SELECT definition,status FROM company_data_update_run WHERE id=?",run);
        require(rows.size()==1,"同步记录不存在");var task=UpdateStore.definition((String)rows.getFirst().get("definition"));
        require(task.mode()==UpdateDefinition.Mode.FEISHU,"仅可核查飞书同步记录");
        try(var lock=UpdateLock.acquire(metadata,UpdateStore.targetKey(task))) {
            require(lock!=null,"仍有执行进程占用目标表，请等待结束");
            tx.executeWithoutResult(status->{
                String current=db.queryForObject("SELECT status FROM company_data_update_run WHERE id=? FOR UPDATE",String.class,run);
                require(Set.of("RUNNING","NEEDS_REVIEW").contains(current),"运行状态已变化，请刷新记录");
                audit.record(CompanyAdminAuditContext.from(request,user),CompanyAuditActionType.DATA_UPDATE_SAVE,"FEISHU_SOURCE",null,null,UpdateStore.json(Map.of("action","RECONCILE","run",run)));
                store.finishSheet(run,"INTERRUPTED",0,"用户确认旧执行结束并完成快照核查，已解除占用",true);
            });
        }
    }
}
