package io.dataease.company.bi.dataupdate;

import io.dataease.company.bi.admin.CompanyAdminGuard;
import io.dataease.company.bi.admin.CompanyAdminAuditContext;
import io.dataease.company.bi.audit.CompanyAuditRecorder;
import io.dataease.company.bi.audit.CompanyAuditActionType;
import io.dataease.company.bi.permission.CompanyCapability;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;

@RestController
@RequestMapping("/company/etl/data-update")
@ConditionalOnProperty(name="company.bi.auth.enabled", havingValue="true", matchIfMissing=true)
public class UpdateController {
    private final CompanyAdminGuard guard;
    private final CompanyAuditRecorder audit;
    private final UpdateStore store;
    private final UpdateRuntime runtime;
    private final UpdateSchedules schedules;
    private final JdbcTemplate db;
    private final UpdateConnections connections;
    public UpdateController(CompanyAdminGuard guard, CompanyAuditRecorder audit, UpdateStore store, UpdateRuntime runtime, UpdateSchedules schedules, DataSource metadata, UpdateConnections connections) {
        this.guard=guard; this.audit=audit; this.store=store; this.runtime=runtime; this.schedules=schedules; this.db=new JdbcTemplate(metadata); this.connections=connections;
    }
    private long admin() { return guard.require(CompanyCapability.ETL_ACCESS); }
    private void record(HttpServletRequest request, long user, CompanyAuditActionType action, String id, Object details) {
        audit.record(CompanyAdminAuditContext.from(request, user), action, "DATA_UPDATE", null, null, UpdateStore.json(Map.of("id", id, "details", details)));
    }
    @GetMapping public Map<String,Object> state() throws Exception {
        admin();
        List<Map<String,Object>> tasks = new ArrayList<>();
        for (UpdateDefinition task : store.all()) tasks.add(Map.of("definition", task, "nextFireTime", schedules.next(task.id()), "effective", task.enabled() && runtime.owns(task) && !store.grouped(task.id()),"grouped",store.grouped(task.id()),"latest",store.latest(task.id())));
        Map<String,Object> result = new LinkedHashMap<>(); result.put("tasks", tasks); result.put("instanceId", runtime.owner()); result.put("hardDisabled", runtime.disabled());
        result.put("datasources", db.query("SELECT id,name,type FROM core_datasource WHERE type IN ('mysql','pg','sqlServer') ORDER BY name",
                (rs,n) -> Map.of("id", rs.getString(1), "name", rs.getString(2), "type", rs.getString(3), "schema", connections.schema(rs.getString(1)))));
        return result;
    }
    @PostMapping public UpdateDefinition save(@RequestBody UpdateDefinition task, HttpServletRequest request) {
        long user = admin();
        String owner = runtime.owner();
        require(!task.enabled() || owner != null, "启用自动更新前请配置 COMPANY_BI_INSTANCE_ID");
        UpdateDefinition normalized = task.normalized(task.id(), task.version(), owner).withSchema(connections.schema(task.datasourceId())); normalized.validate();
        validSource(normalized.datasourceId()); validSource(normalized.sourceDatasourceId());
        return store.save(normalized, owner, () -> record(request, user, CompanyAuditActionType.DATA_UPDATE_SAVE,
                task.id() == null ? "new" : task.id(), Map.of("datasourceId", task.datasourceId(), "table", task.table(), "sqlHash", UpdateStore.hash(task.sql()), "version", task.version()+1)));
    }
    private void validSource(String id) {
        require(id != null && db.queryForObject("SELECT COUNT(*) FROM core_datasource WHERE id=? AND type IN ('mysql','pg','sqlServer')", Integer.class, id) == 1, "数据源不存在或类型不支持");
    }
    public record RunRequest(String businessDate, long version) { }
    @DeleteMapping("/{id}") public void delete(@PathVariable String id, @RequestParam long version, HttpServletRequest request) {
        long user=admin(); store.delete(id,version,()->record(request,user,CompanyAuditActionType.DATA_UPDATE_DELETE,id,Map.of("version",version)));
    }
    @PostMapping("/{id}/run") public Map<String,String> run(@PathVariable String id, @RequestBody RunRequest body, HttpServletRequest request) {
        long user = admin(); UpdateDefinition task = store.get(id);
        require(task.version() == body.version(), "配置已变化，请重新加载");
        LocalDate date;
        try { date = body.businessDate() == null || body.businessDate().isBlank() ? task.businessDate(Instant.now()) : LocalDate.parse(body.businessDate()); }
        catch (Exception e) { throw new IllegalArgumentException("业务日期无效"); }
        require(date.getYear() >= 1970 && date.getYear() <= 9999, "业务日期超出支持范围");
        String run = runtime.submit(task, "MANUAL", Instant.now(), date,
                () -> record(request, user, CompanyAuditActionType.DATA_UPDATE_RUN, id, Map.of("businessDate", date.toString(), "version", task.version())));
        return Map.of("id", run);
    }
    @GetMapping("/{id}/runs") public List<UpdateStore.Run> runs(@PathVariable String id, @RequestParam(defaultValue="50") int limit) { admin(); store.get(id); return store.history(id,limit); }
    @PostMapping("/{id}/runs/{run}/cancel") public void cancel(@PathVariable String id, @PathVariable String run, HttpServletRequest request) {
        long user = admin(); store.cancel(id, run, () -> record(request,user,CompanyAuditActionType.DATA_UPDATE_CANCEL,id,Map.of("run",run)));
    }
}
