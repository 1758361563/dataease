package io.dataease.company.bi.dataupdate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;

@Component
@ConditionalOnProperty(name="company.bi.auth.enabled", havingValue="true", matchIfMissing=true)
public class UpdateStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public UpdateStore(DataSource source) {
        db = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    public static String json(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("配置序列化失败", e); }
    }
    public static UpdateDefinition definition(String value) {
        try { return JSON.readValue(value, UpdateDefinition.class); } catch (Exception e) { throw new IllegalStateException("更新配置损坏", e); }
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String targetKey(UpdateDefinition task) {
        return hash(json(List.of(task.datasourceId(), task.table())));
    }
    public List<UpdateDefinition> all() {
        return db.query("SELECT definition FROM company_data_update_task ORDER BY updated_at DESC", (rs, n) -> definition(rs.getString(1)));
    }
    public UpdateDefinition get(String id) {
        var tasks = db.query("SELECT definition FROM company_data_update_task WHERE id=?", (rs, n) -> definition(rs.getString(1)), id);
        require(!tasks.isEmpty(), "数据更新任务不存在"); return tasks.getFirst();
    }
    public boolean grouped(String id) {return db.queryForObject("SELECT COUNT(*) FROM company_data_update_member WHERE task_id=?",Integer.class,id)>0;}
    public boolean claimed(UpdateDefinition task) {return db.queryForObject("SELECT COUNT(*) FROM company_data_update_claim WHERE target_key=?",Integer.class,targetKey(task))>0;}
    public Map<String,String> latest(String taskId) {
        String union="SELECT status,finished_at,requested_at AS event_at FROM company_data_update_run WHERE task_id=? UNION ALL SELECT n.status,n.finished_at,COALESCE(n.started_at,r.created_at) AS event_at FROM company_data_update_group_node n JOIN company_data_update_group_run r ON r.id=n.run_id WHERE n.task_id=?";
        List<String> statuses=db.query("SELECT status FROM ("+union+") r ORDER BY event_at DESC LIMIT 1",(rs,n)->rs.getString(1),taskId,taskId);
        String success=db.queryForObject("SELECT CAST(MAX(finished_at) AS CHAR) FROM ("+union+") r WHERE status='SUCCESS'",String.class,taskId,taskId);
        return Map.of("status",statuses.isEmpty()?"":statuses.getFirst(),"lastSuccessAt",Objects.toString(success,""));
    }
    public UpdateDefinition save(UpdateDefinition incoming, String owner, Runnable audit) {
        return tx.execute(status -> {
            // Serialize graph edits across instances, including first-time task creation.
            db.update("INSERT IGNORE INTO company_data_update_guard(id) VALUES (1)");
            db.queryForObject("SELECT id FROM company_data_update_guard WHERE id=1 FOR UPDATE", Integer.class);
            boolean create = incoming.id() == null || incoming.id().isBlank();
            String id = create ? UUID.randomUUID().toString() : incoming.id();
            UpdateDefinition old = create ? null : get(id);
            require(create ? incoming.version() == 0 : old.version() == incoming.version(), "配置已被修改，请重新加载");
            UpdateDefinition saved = incoming.normalized(id, incoming.version() + 1, owner);
            saved.validate();
            if (old != null) require(targetKey(old).equals(targetKey(saved)) && old.schema().equals(saved.schema()), "已保存任务的目标表不能改变；数据源 schema 变化后请重新配置任务");
            require(db.queryForObject("SELECT COUNT(*) FROM company_data_update_task WHERE target_key=? AND id<>?", Integer.class, targetKey(saved), id) == 0, "此表已配置数据更新，请重新加载");
            Map<String, UpdateDefinition> graph = new LinkedHashMap<>(); all().forEach(task -> graph.put(task.id(), task)); graph.put(id, saved);
            UpdateDefinition.validateGraph(graph);
            Map<String,String> memberships=new HashMap<>();
            db.query("SELECT task_id,group_id FROM company_data_update_member",rs->{memberships.put(rs.getString(1),rs.getString(2));});
            for(var entry:graph.values())for(String dependency:entry.dependencies()) {
                String left=memberships.get(entry.id()),right=memberships.get(dependency);
                require(Objects.equals(left,right),"依赖必须位于同一任务组；请先调整组成员或依赖");
            }
            require(db.queryForObject("SELECT COUNT(*) FROM company_data_update_run WHERE task_id=? AND status IN ('QUEUED','RUNNING')", Integer.class, id) == 0,
                    "任务执行期间不能修改配置");
            if (create) db.update("INSERT INTO company_data_update_task(id,target_key,version,definition,updated_at) VALUES (?,?,?,?,UTC_TIMESTAMP(3))",
                    id, targetKey(saved), saved.version(), json(saved));
            else db.update("UPDATE company_data_update_task SET version=?,definition=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?", saved.version(), json(saved), id);
            audit.run(); return saved;
        });
    }
    public record Run(String id, String taskId, long version, String triggerType, String businessDate,
                      String status, String requestedAt, String startedAt, String finishedAt, long rows, String message) { }
    public void delete(String id, long version, Runnable audit) {
        tx.executeWithoutResult(status -> {
            db.queryForObject("SELECT id FROM company_data_update_guard WHERE id=1 FOR UPDATE", Integer.class);
            db.queryForObject("SELECT version FROM company_data_update_task WHERE id=? FOR UPDATE", Long.class, id);
            require(get(id).version() == version, "配置已被修改，请重新加载");
            require(!grouped(id),"请先将任务移出任务组");
            require(all().stream().noneMatch(task -> task.dependencies().contains(id)), "此任务被其他表依赖，请先移除依赖关系");
            require(db.queryForObject("SELECT COUNT(*) FROM company_data_update_run WHERE task_id=? AND status IN ('QUEUED','RUNNING')", Integer.class, id) == 0, "请先取消当前更新再删除配置");
            audit.run(); db.update("DELETE FROM company_data_update_task WHERE id=?", id);
        });
    }
    public String skipped(UpdateDefinition task, String key, LocalDate date, String message) {
        String id=UUID.randomUUID().toString(), triggerKey=hash(key);
        db.update("""
                INSERT IGNORE INTO company_data_update_run(id,task_id,task_version,trigger_key,trigger_type,business_date,definition,upstream_runs,status,requested_at,finished_at,message)
                VALUES (?,?,?,?,'CRON',?,?,'{}','SKIPPED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?)
                """, id,task.id(),task.version(),triggerKey,date.toString(),json(task),message);
        return db.queryForObject("SELECT id FROM company_data_update_run WHERE task_id=? AND trigger_key=?",String.class,task.id(),triggerKey);
    }
    public List<Run> history(String taskId, int limit) {
        return db.query("SELECT * FROM company_data_update_run WHERE task_id=? ORDER BY requested_at DESC,id DESC LIMIT ?", (rs,n) ->
                new Run(rs.getString("id"), taskId, rs.getLong("task_version"), rs.getString("trigger_type"), rs.getString("business_date"),
                        rs.getString("status"), rs.getString("requested_at"), rs.getString("started_at"), rs.getString("finished_at"), rs.getLong("row_count"), rs.getString("message")), taskId, Math.min(100, Math.max(1, limit)));
    }
    public Map<String, String> upstreamRuns(UpdateDefinition task, LocalDate date) {
        Map<String, String> result = new TreeMap<>();
        for (String upstream : task.dependencies()) {
            List<String> ids = db.query("""
                    SELECT r.id FROM company_data_update_run r JOIN company_data_update_task t ON t.id=r.task_id
                    WHERE r.task_id=? AND r.business_date=? AND r.status='SUCCESS' AND r.task_version=t.version
                    ORDER BY r.finished_at DESC,r.id DESC LIMIT 1
                    """, (rs,n) -> rs.getString(1), upstream, date.toString());
            if (ids.isEmpty()) return null;
            result.put(upstream, ids.getFirst());
        }
        return result;
    }
    public String enqueue(UpdateDefinition task, String type, String key, LocalDate date, Map<String,String> upstream, Runnable audit) {
        return tx.execute(status -> {
            db.update("INSERT IGNORE INTO company_data_update_guard VALUES(1)");
            db.queryForObject("SELECT id FROM company_data_update_guard WHERE id=1 FOR UPDATE",Integer.class);
            db.queryForObject("SELECT version FROM company_data_update_task WHERE id=? FOR UPDATE", Long.class, task.id());
            require(!grouped(task.id()),"此任务由任务组执行，请运行整组");
            require(!claimed(task),"目标表被任务批次或建表操作占用");
            require(get(task.id()).version() == task.version(), "配置已变化，请重新运行");
            String triggerKey = hash(key);
            List<String> existing = db.query("SELECT id FROM company_data_update_run WHERE task_id=? AND trigger_key=?", (rs,n) -> rs.getString(1), task.id(), triggerKey);
            if (!existing.isEmpty()) return existing.getFirst();
            require(db.queryForObject("SELECT COUNT(*) FROM company_data_update_run WHERE task_id=? AND status IN ('QUEUED','RUNNING')", Integer.class, task.id()) == 0, "此表已有等待或运行中的更新");
            String id = UUID.randomUUID().toString();
            db.update("""
                    INSERT INTO company_data_update_run(id,task_id,task_version,trigger_key,trigger_type,business_date,definition,upstream_runs,status,requested_at)
                    VALUES (?,?,?,?,?,?,?,?,'QUEUED',UTC_TIMESTAMP(3))
                    """, id, task.id(), task.version(), triggerKey, type, date.toString(), json(task), json(upstream));
            audit.run(); return id;
        });
    }
    public record Pending(String id, UpdateDefinition definition, LocalDate date) { }
    public List<LocalDate> dependencyDates(UpdateDefinition task) {
        if (task.dependencies().isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(); args.add(task.id()); args.addAll(task.dependencies()); args.add(task.id()); args.add(task.version());
        return db.query("""
                SELECT DISTINCT r.business_date FROM company_data_update_run r
                JOIN company_data_update_task t ON t.id=?
                WHERE r.task_id IN (%s) AND r.status='SUCCESS' AND r.finished_at>=t.updated_at
                AND NOT EXISTS (SELECT 1 FROM company_data_update_run consumed
                    WHERE consumed.task_id=? AND consumed.task_version=? AND consumed.business_date=r.business_date
                    AND consumed.requested_at>=r.finished_at)
                ORDER BY r.business_date DESC LIMIT 100
                """.formatted(String.join(",", Collections.nCopies(task.dependencies().size(), "?"))),
                (rs,n) -> rs.getDate(1).toLocalDate(), args.toArray());
    }
    public List<Pending> pending(String owner) {
        return db.query("SELECT id,definition,business_date FROM company_data_update_run WHERE status='QUEUED' AND JSON_UNQUOTE(JSON_EXTRACT(definition,'$.owner'))=? ORDER BY requested_at LIMIT 100",
                (rs,n) -> new Pending(rs.getString(1), definition(rs.getString(2)), rs.getDate(3).toLocalDate()), owner);
    }
    public boolean start(String id) {
        return db.update("UPDATE company_data_update_run SET status='RUNNING',started_at=UTC_TIMESTAMP(3) WHERE id=? AND status='QUEUED' AND cancel_requested=0", id) == 1;
    }
    public void finish(String id, String status, long rows, String message) {
        db.update("UPDATE company_data_update_run SET status=?,row_count=?,message=?,finished_at=UTC_TIMESTAMP(3) WHERE id=? AND status IN ('QUEUED','RUNNING')", status, rows, message, id);
    }
    public boolean cancelled(String id) { return Boolean.TRUE.equals(db.queryForObject("SELECT cancel_requested FROM company_data_update_run WHERE id=?", Boolean.class, id)); }
    public void cancel(String taskId, String id, Runnable audit) {
        tx.executeWithoutResult(status -> {
            require(db.update("UPDATE company_data_update_run SET cancel_requested=1 WHERE id=? AND task_id=? AND status IN ('QUEUED','RUNNING')", id, taskId) == 1, "运行已结束或不存在");
            db.update("UPDATE company_data_update_run SET status='CANCELLED',finished_at=UTC_TIMESTAMP(3),message='已取消等待' WHERE id=? AND status='QUEUED'", id);
            audit.run();
        });
    }
    /** Called only while holding the task's distributed lock. No automatic replay after a crash. */
    public void interruptOrphans(String taskId) {
        db.update("UPDATE company_data_update_run SET status='INTERRUPTED',finished_at=UTC_TIMESTAMP(3),message='执行进程已中断，请确认目标表状态后手动重跑' WHERE task_id=? AND status='RUNNING'", taskId);
    }
    public List<Pending> running() {
        return db.query("SELECT id,definition,business_date FROM company_data_update_run WHERE status='RUNNING'", (rs,n) -> new Pending(rs.getString(1),definition(rs.getString(2)),rs.getDate(3).toLocalDate()));
    }
}
