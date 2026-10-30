package io.dataease.company.bi.dataupdate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;

@Component
@ConditionalOnProperty(name="company.bi.auth.enabled",havingValue="true",matchIfMissing=true)
public class UpdateGroups {
    public record Definition(String id,long version,String datasourceId,String name,List<String> tasks,boolean enabled,String cron,String timezone,String owner) { }
    public record Snapshot(Definition group,List<UpdateDefinition> tasks) { }
    public record Pending(String id,String date,String status,Snapshot snapshot) { }
    private static final ObjectMapper JSON=new ObjectMapper();
    final JdbcTemplate db; final TransactionTemplate tx; private final UpdateStore tasks;
    public UpdateGroups(DataSource metadata,UpdateStore tasks) {db=new JdbcTemplate(metadata);tx=new TransactionTemplate(new DataSourceTransactionManager(metadata));this.tasks=tasks;}
    private static <T>T parse(String value,Class<T> type) {try{return JSON.readValue(value,type);}catch(Exception e){throw new IllegalStateException("任务组配置损坏",e);}}
    private void guard(){db.update("INSERT IGNORE INTO company_data_update_guard VALUES(1)");db.queryForObject("SELECT id FROM company_data_update_guard WHERE id=1 FOR UPDATE",Integer.class);}
    public List<Definition> all(){return db.query("SELECT definition FROM company_data_update_group ORDER BY updated_at DESC",(rs,n)->parse(rs.getString(1),Definition.class));}
    public Definition get(String id){return all().stream().filter(group->group.id().equals(id)).findFirst().orElseThrow(()->new UpdateDefinition.ValidationException("任务组不存在"));}
    public Definition save(Definition body,String owner,java.util.function.Consumer<Definition> audit) {
        require(body.name()!=null&&!body.name().isBlank()&&body.name().length()<=100,"请输入任务组名称（最多 100 字）");
        require(body.tasks()!=null&&!body.tasks().isEmpty()&&body.tasks().size()<=64&&new HashSet<>(body.tasks()).size()==body.tasks().size(),"请选择 1–64 个不重复的任务");
        require(!body.enabled()||owner!=null,"请先配置当前实例标识");
        try {
            var cron=new org.quartz.CronExpression(body.cron());cron.setTimeZone(TimeZone.getTimeZone(ZoneId.of(body.timezone())));
            require(cron.getNextValidTimeAfter(new Date())!=null,"任务组 Cron 没有未来执行时间");
        }catch(java.text.ParseException|DateTimeException e){throw new UpdateDefinition.ValidationException("任务组 Cron 或时区无效");}
        return tx.execute(status->{
            guard();boolean create=body.id()==null||body.id().isBlank();String id=create?UUID.randomUUID().toString():body.id();
            require(create?body.version()==0:get(id).version()==body.version(),"任务组已被修改，请重新加载");
            Map<String,UpdateDefinition> members=new LinkedHashMap<>();
            for(String taskId:body.tasks()) {
                var task=tasks.get(taskId);require(task.datasourceId().equals(body.datasourceId()),"组内任务必须属于同一目标数据源");members.put(taskId,task);
                require(db.queryForObject("SELECT COUNT(*) FROM company_data_update_member WHERE task_id=? AND group_id<>?",Integer.class,taskId,id)==0,"任务已属于另一任务组");
                require(db.queryForObject("SELECT COUNT(*) FROM company_data_update_run WHERE task_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,taskId)==0,"请等待独立任务结束后加入组");
            }
            ordered(members);
            for(var task:tasks.all()) if(!members.containsKey(task.id())) require(Collections.disjoint(task.dependencies(),members.keySet()),"还有组外任务依赖这些输入，请一起加入组或调整依赖");
            require(db.queryForObject("SELECT COUNT(*) FROM company_data_update_group_run WHERE group_id=? AND status IN ('QUEUED','RUNNING','NEEDS_REVIEW')",Integer.class,id)==0,"请等待或处理当前批次后修改任务组成员");
            Definition saved=new Definition(id,body.version()+1,body.datasourceId(),body.name().strip(),List.copyOf(body.tasks()),body.enabled(),body.cron(),body.timezone(),owner);
            if(create)db.update("INSERT INTO company_data_update_group VALUES(?,?,?,UTC_TIMESTAMP(3))",id,saved.version(),UpdateStore.json(saved));
            else db.update("UPDATE company_data_update_group SET version=?,definition=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",saved.version(),UpdateStore.json(saved),id);
            db.update("DELETE FROM company_data_update_member WHERE group_id=?",id);
            for(String taskId:body.tasks())db.update("INSERT INTO company_data_update_member VALUES(?,?)",taskId,id);
            audit.accept(saved);return saved;
        });
    }
    public static List<UpdateDefinition> ordered(Map<String,UpdateDefinition> members) {
        UpdateDefinition.validateGraph(members);List<UpdateDefinition> result=new ArrayList<>();Set<String> done=new HashSet<>();
        while(result.size()<members.size())for(var task:members.values())if(!done.contains(task.id())&&done.containsAll(task.dependencies())){result.add(task);done.add(task.id());}
        return result;
    }
    public String submit(String groupId,long version,String date,String trigger,String owner,java.util.function.Consumer<String> audit) {
        require(owner!=null,"请配置当前实例标识");LocalDate.parse(date);
        return tx.execute(status->{
            guard();Definition group=get(groupId);require(group.version()==version,"任务组版本已变化");
            String key=UpdateStore.hash(trigger);
            var existing=db.queryForList("SELECT id FROM company_data_update_group_run WHERE group_id=? AND trigger_key=?",String.class,groupId,key);if(!existing.isEmpty())return existing.getFirst();
            Map<String,UpdateDefinition> members=new LinkedHashMap<>();for(String id:group.tasks())members.put(id,tasks.get(id));
            List<UpdateDefinition> ordered=ordered(members);String run=UUID.randomUUID().toString();
            for(var task:ordered) {
                require(!tasks.claimed(task),"目标表已被另一批次或建表操作占用");
                require(db.queryForObject("SELECT COUNT(*) FROM company_data_update_run WHERE task_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,task.id())==0,"目标表存在独立更新，请等待结束");
                db.update("INSERT INTO company_data_update_claim VALUES(?,?,UTC_TIMESTAMP(3))",UpdateStore.targetKey(task),run);
            }
            db.update("INSERT INTO company_data_update_group_run(id,group_id,trigger_key,snapshot,owner_id,business_date,status,created_at) VALUES(?,?,?,?,?,?,'QUEUED',UTC_TIMESTAMP(3))",run,groupId,key,UpdateStore.json(new Snapshot(group,ordered)),owner,date);
            for(var task:ordered)db.update("INSERT INTO company_data_update_group_node(run_id,task_id,status) VALUES(?,?,'WAITING')",run,task.id());
            audit.accept(run);return run;
        });
    }
    public void skipped(Definition group,String date,String trigger,String owner,String message) {
        db.update("INSERT IGNORE INTO company_data_update_group_run(id,group_id,trigger_key,snapshot,owner_id,business_date,status,message,created_at,finished_at) VALUES(?,?,?,?,?,?,'SKIPPED',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                UUID.randomUUID().toString(),group.id(),UpdateStore.hash(trigger),UpdateStore.json(new Snapshot(group,List.of())),owner,date,message);
    }
    public List<Pending> pending(String owner){return db.query("SELECT id,business_date,status,snapshot FROM company_data_update_group_run WHERE owner_id=? AND status IN ('QUEUED','RUNNING') ORDER BY created_at",(rs,n)->new Pending(rs.getString(1),rs.getString(2),rs.getString(3),parse(rs.getString(4),Snapshot.class)),owner);}
    public boolean start(String id){return db.update("UPDATE company_data_update_group_run SET status='RUNNING' WHERE id=? AND status='QUEUED'",id)==1;}
    public boolean cancelled(String id){return Boolean.TRUE.equals(db.queryForObject("SELECT cancel_requested FROM company_data_update_group_run WHERE id=?",Boolean.class,id));}
    public void node(String run,String task,String status,long rows,String message){db.update("UPDATE company_data_update_group_node SET status=?,row_count=?,message=?,started_at=COALESCE(started_at,UTC_TIMESTAMP(3)),finished_at=IF(?='RUNNING',NULL,UTC_TIMESTAMP(3)) WHERE run_id=? AND task_id=?",status,rows,message,status,run,task);}
    public void finish(String run,String result,String message,boolean release){tx.executeWithoutResult(status->{
        db.update("UPDATE company_data_update_group_run SET status=?,message=?,finished_at=UTC_TIMESTAMP(3) WHERE id=?",result,message,run);
        db.update("UPDATE company_data_update_group_node SET status=IF(status='RUNNING','INTERRUPTED','BLOCKED'),finished_at=UTC_TIMESTAMP(3) WHERE run_id=? AND status IN ('WAITING','RUNNING')",run);
        if(release)db.update("DELETE FROM company_data_update_claim WHERE owner_id=?",run);
    });}
    public List<Map<String,Object>> history(String group){return db.query("SELECT id,business_date,status,message,created_at,finished_at FROM company_data_update_group_run WHERE group_id=? ORDER BY created_at DESC LIMIT 50",(rs,n)->{
        Map<String,Object> row=new LinkedHashMap<>();for(String field:List.of("id","business_date","status","message","created_at","finished_at"))row.put(field,rs.getString(field));return row;
    },group);}
    public Map<String,Object> detail(String run){var rows=db.queryForList("SELECT * FROM company_data_update_group_run WHERE id=?",run);require(!rows.isEmpty(),"批次不存在");var result=rows.getFirst();result.put("nodes",db.queryForList("SELECT * FROM company_data_update_group_node WHERE run_id=?",run));return result;}
    public void cancel(String run,Runnable audit){tx.executeWithoutResult(status->{
        require(db.update("UPDATE company_data_update_group_run SET cancel_requested=1 WHERE id=? AND status IN ('QUEUED','RUNNING')",run)==1,"批次已结束或不存在");
        if(db.update("UPDATE company_data_update_group_run SET status='CANCELLED',finished_at=UTC_TIMESTAMP(3),message='已取消等待' WHERE id=? AND status='QUEUED'",run)==1){
            db.update("UPDATE company_data_update_group_node SET status='BLOCKED',finished_at=UTC_TIMESTAMP(3) WHERE run_id=?",run);
            db.update("DELETE FROM company_data_update_claim WHERE owner_id=?",run);
        }
        audit.run();
    });}
    public void delete(String id,long version,Runnable audit){tx.executeWithoutResult(status->{guard();require(get(id).version()==version,"任务组版本已变化");require(db.queryForObject("SELECT COUNT(*) FROM company_data_update_group_run WHERE group_id=? AND status IN ('QUEUED','RUNNING','NEEDS_REVIEW')",Integer.class,id)==0,"请先处理当前批次");db.update("DELETE FROM company_data_update_member WHERE group_id=?",id);db.update("DELETE FROM company_data_update_group WHERE id=?",id);audit.run();});}
}
