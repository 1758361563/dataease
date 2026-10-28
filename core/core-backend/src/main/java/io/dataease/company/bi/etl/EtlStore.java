package io.dataease.company.bi.etl;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import javax.sql.DataSource;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import static io.dataease.company.bi.etl.EtlModel.*;

@Component
@ConditionalOnProperty(name="company.bi.auth.enabled", havingValue="true", matchIfMissing=true)
public class EtlStore {
    final JdbcTemplate db;
    final TransactionTemplate tx;
    public EtlStore(DataSource source) { db = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source)); }
    public record Setting(boolean schedulerEnabled, String enabledInstanceId, String dailyAt, boolean chainEnabled,
                          Long refreshDatasourceId, Long activePackageId, Long activeTestPackageId) {}
    public void initialize() {
        db.update("INSERT IGNORE INTO company_etl_setting(id) VALUES (1)");
        for (int i = 0; i < EtlPackageValidator.NAMES.size(); i++)
            db.update("INSERT IGNORE INTO company_etl_task(etl_name,enabled,sort_order) VALUES (?,1,?)", EtlPackageValidator.NAMES.get(i), i);
    }
    public Setting setting() {
        return db.queryForObject("SELECT * FROM company_etl_setting WHERE id=1", (r,n) -> new Setting(r.getBoolean("scheduler_enabled"),
                r.getString("enabled_instance_id"), r.getString("daily_at"), r.getBoolean("chain_enabled"),
                r.getObject("refresh_datasource_id", Long.class), r.getObject("active_package_id", Long.class), r.getObject("active_test_package_id", Long.class)));
    }
    public List<Map<String,Object>> tasks() { return db.queryForList("SELECT * FROM company_etl_task ORDER BY sort_order"); }
    public Set<String> enabledTasks() {
        return new LinkedHashSet<>(db.queryForList("SELECT etl_name FROM company_etl_task WHERE enabled=1 ORDER BY sort_order", String.class));
    }
    public void configure(String daily, boolean chain, Long datasource) {
        require(daily != null && daily.matches("(?:[01]\\d|2[0-3]):[0-5]\\d"), "执行时间必须为 HH:mm");
        Setting old = setting();
        require(!old.schedulerEnabled() || Objects.equals(old.refreshDatasourceId(), datasource), "更换刷新数据源前请关闭全局开关");
        db.update("UPDATE company_etl_setting SET daily_at=?,chain_enabled=?,refresh_datasource_id=?,updated_at=? WHERE id=1",
                daily, chain, datasource, time(Instant.now()));
    }
    public void setSwitch(boolean enabled, String instance, long operator) {
        db.update("UPDATE company_etl_setting SET scheduler_enabled=?,enabled_instance_id=?,enabled_by=?,enabled_at=?,updated_at=? WHERE id=1",
                enabled, enabled ? instance : null, operator, time(Instant.now()), time(Instant.now()));
    }
    public void task(String name, boolean enabled) {
        require(EtlPackageValidator.NAMES.contains(name), "任务不存在");
        db.update("UPDATE company_etl_task SET enabled=? WHERE etl_name=?", enabled, name);
    }
    public List<Map<String,Object>> datasources() {
        return db.queryForList("SELECT CAST(id AS CHAR) AS id,name,type FROM core_datasource WHERE type='mysql' ORDER BY name");
    }
    public List<Map<String,Object>> packages() {
        return db.queryForList("SELECT id,package_kind,run_kind,file_sha256,source_commit,generated_at,imported_by,imported_at,status,reject_reason,init_status FROM company_etl_package ORDER BY id DESC LIMIT 200");
    }
    public long savePackage(byte[] archive, Map<String,String> files, Manifest manifest, String rejection, long operator) {
        return tx.execute(status -> {
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            db.update(c -> {
                var p = c.prepareStatement("INSERT INTO company_etl_package(package_kind,run_kind,file_sha256,source_commit,generated_at,manifest_json,imported_by,imported_at,status,reject_reason,archive_bytes) VALUES (?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                p.setString(1, manifest == null ? "unknown" : manifest.packageKind()); p.setString(2, manifest == null ? "unknown" : manifest.runKind());
                p.setString(3, EtlPackageValidator.hash(archive)); p.setString(4, manifest == null ? null : manifest.source().commit());
                p.setString(5, manifest == null ? null : manifest.source().generatedAt()); p.setString(6, files.getOrDefault("manifest.json", "{}"));
                p.setLong(7, operator); p.setString(8, time(Instant.now())); p.setString(9, rejection == null ? "imported" : "rejected"); p.setString(10, rejection);
                p.setBytes(11, archive); return p;
            }, key);
            long id = Objects.requireNonNull(key.getKey()).longValue();
            files.forEach((path, content) -> db.update("INSERT INTO company_etl_package_file(package_id,path,sha256,content) VALUES (?,?,?,?)", id, path,
                    EtlPackageValidator.hash(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)), content));
            return id;
        });
    }
    public List<Map<String,Object>> files(long id) {
        return db.queryForList("SELECT path,sha256,content FROM company_etl_package_file WHERE package_id=? ORDER BY path", id);
    }
    public SqlPackage load(long id) {
        Map<String,Object> row = db.queryForMap("SELECT manifest_json,status FROM company_etl_package WHERE id=?", id);
        require(!"rejected".equals(row.get("status")), "被拒绝的 SQL 包不能运行");
        Map<String,String> files = new LinkedHashMap<>();
        for (Map<String,Object> file : files(id)) files.put((String)file.get("path"), (String)file.get("content"));
        // Recheck hashes when loading, before any SQL is executed.
        return new SqlPackage(id, EtlPackageValidator.validate(files), Map.copyOf(files));
    }
    public void activate(long id) {
        SqlPackage pack = load(id);
        tx.executeWithoutResult(status -> {
            db.queryForObject("SELECT id FROM company_etl_setting WHERE id=1 FOR UPDATE", Long.class);
            String column = "test".equals(pack.manifest().packageKind()) ? "active_test_package_id" : "active_package_id";
            db.update("UPDATE company_etl_package SET status='retired' WHERE package_kind=? AND status='active'", pack.manifest().packageKind());
            db.update("UPDATE company_etl_package SET status='active' WHERE id=?", id);
            db.update("UPDATE company_etl_setting SET " + column + "=? WHERE id=1", id);
        });
    }
    public record Batch(String plannedAt, String kind, long packageId, long datasourceId, String windowStart,
                        String windowEnd, List<String> names, String triggerType, boolean single) {}
    public Batch pin(Batch batch) {
        db.update("INSERT IGNORE INTO company_etl_batch(planned_at,kind,package_id,datasource_id,window_start,window_end,names_json,trigger_type,single_task) VALUES (?,?,?,?,?,?,?,?,?)",
                batch.plannedAt(),batch.kind(),batch.packageId(),batch.datasourceId(),batch.windowStart(),batch.windowEnd(),json(batch.names()),batch.triggerType(),batch.single());
        return batch(batch.plannedAt(), batch.kind());
    }
    public Batch batch(String planned, String kind) {
        var rows = db.query("SELECT * FROM company_etl_batch WHERE planned_at=? AND kind=?", (r,n) -> new Batch(r.getString("planned_at"),r.getString("kind"),r.getLong("package_id"),
                r.getLong("datasource_id"),r.getString("window_start"),r.getString("window_end"),Arrays.asList(read(r.getString("names_json"),String[].class)),r.getString("trigger_type"),r.getBoolean("single_task")),planned,kind);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public boolean beginInit(long id) { return db.update("UPDATE company_etl_package SET init_status='running' WHERE id=? AND init_status='pending'",id) == 1; }
    public void endInit(long id, boolean ok) { db.update("UPDATE company_etl_package SET init_status=? WHERE id=? AND init_status='running'",ok ? "success" : "fail",id); }
}
