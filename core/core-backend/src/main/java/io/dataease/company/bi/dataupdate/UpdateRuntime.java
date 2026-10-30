package io.dataease.company.bi.dataupdate;

import io.dataease.company.bi.etl.EtlSwitchService;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;

@Component
@ConditionalOnProperty(name="company.bi.auth.enabled", havingValue="true", matchIfMissing=true)
public class UpdateRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(UpdateRuntime.class);
    private final UpdateStore store;
    private final UpdateExecutor executor;
    private final DataSource metadata;
    private final EtlSwitchService identity;
    private final Environment environment;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("table-update-", 0).factory());
    private final ScheduledExecutorService monitor = Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("table-update-monitor-", 0).factory());
    private final Set<String> dispatched = ConcurrentHashMap.newKeySet();
    private final Map<String, UpdateExecution> active = new ConcurrentHashMap<>();
    private volatile boolean ready;
    public UpdateRuntime(UpdateStore store, UpdateExecutor executor, DataSource metadata, EtlSwitchService identity, Environment environment) {
        this.store = store; this.executor = executor; this.metadata = metadata; this.identity = identity; this.environment = environment;
    }
    public void ready() { ready = true; }
    public String owner() { return identity.instanceId(); }
    public boolean disabled() { return Boolean.parseBoolean(environment.getProperty("COMPANY_BI_DATA_UPDATE_HARD_DISABLE", "false")); }
    public boolean owns(UpdateDefinition task) { return !disabled() && owner() != null && owner().equals(task.owner()); }
    public String submit(UpdateDefinition task, String type, Instant planned, LocalDate date, Runnable audit) {
        require(ready, "数据更新服务尚未就绪");
        require(!disabled(), "运维已关闭数据更新执行");
        require(owner() != null, "请配置 COMPANY_BI_INSTANCE_ID 后执行数据更新");
        if (!"MANUAL".equals(type)) require(task.enabled() && owns(task), "自动更新未在当前实例启用");
        Map<String,String> upstream = store.upstreamRuns(task, date);
        String key = switch (type) {
            case "CRON" -> "cron:" + task.version() + ":" + planned;
            case "DEPENDENCY" -> "dependency:" + task.version() + ":" + date + ":" + UpdateStore.json(upstream);
            default -> "manual:" + UUID.randomUUID();
        };
        if (upstream == null && "CRON".equals(type)) return store.skipped(task, key, date, "同一业务日期的上游任务尚未全部成功");
        require(upstream != null, "同一业务日期的上游任务尚未全部成功");
        // Manual executions are explicitly bound to the requesting instance as well.
        try { return store.enqueue(task.normalized(task.id(), task.version(), owner()), type, key, date, upstream, audit); }
        catch (IllegalArgumentException e) {
            if ("CRON".equals(type)) return store.skipped(task, key, date, e.getMessage());
            throw e;
        }
    }
    @Scheduled(fixedDelay=5000, initialDelay=5000)
    public void tick() {
        if (!ready) return;
        try {
            if (disabled()) { active.values().forEach(run -> run.stop("运维已关闭数据更新执行")); return; }
            for (UpdateStore.Pending pending : store.pending(owner())) {
                if (dispatched.size() >= 2) break;
                if (owns(pending.definition()) && dispatched.add(pending.id())) workers.submit(() -> run(pending));
            }
            // An orphan is reported, never automatically replayed. A process that still
            // owns the metadata lock keeps its RUNNING record untouched.
            for (UpdateStore.Pending running : store.running()) {
                if (dispatched.contains(running.id())) continue;
                try (UpdateLock lock = UpdateLock.acquire(metadata, UpdateStore.targetKey(running.definition()))) {
                    if (lock != null) store.interruptOrphans(running.definition().id());
                }
            }
            for (UpdateDefinition task : store.all()) {
                if (!task.enabled() || task.trigger() != UpdateDefinition.Trigger.DEPENDENCY || !owns(task) || store.grouped(task.id())) continue;
                // Reconcile from durable successful runs, including backfills and a crash
                // between committing an upstream result and dispatching its dependents.
                for (LocalDate date : store.dependencyDates(task)) {
                    if (store.upstreamRuns(task, date) == null) continue;
                    try { submit(task, "DEPENDENCY", Instant.now(), date, () -> { }); }
                    catch (IllegalArgumentException ignored) { /* A run already occupies this table. */ }
                }
            }
        } catch (Exception e) { LOG.warn("Data update dispatch failed ({})", e.getClass().getSimpleName()); }
    }
    private void run(UpdateStore.Pending pending) {
        UpdateExecution execution = new UpdateExecution(pending.definition().timeoutSeconds());
        try (UpdateLock lock = UpdateLock.acquire(metadata, UpdateStore.targetKey(pending.definition()))) {
            if (lock == null) return;
            if (!owns(pending.definition())) return;
            if (store.claimed(pending.definition()) || store.grouped(pending.definition().id())) {
                store.finish(pending.id(),"SKIPPED",0,"目标表已由任务组或建表操作接管");return;
            }
            store.interruptOrphans(pending.definition().id());
            if (!store.start(pending.id())) return;
            execution.commitGuard(() -> lock.alive() && !disabled() && !store.cancelled(pending.id()));
            active.put(pending.id(), execution);
            ScheduledFuture<?> watchdog = monitor.scheduleWithFixedDelay(() -> {
                try {
                    if (!lock.alive()) execution.stop("执行锁已丢失");
                    else if (disabled()) execution.stop("运维已关闭数据更新执行");
                    else if (store.cancelled(pending.id())) execution.stop("运行已取消");
                    execution.check();
                } catch (Exception e) { execution.stop("运行监控中断或执行超时"); }
            }, 0, 1, TimeUnit.SECONDS);
            try {
                long rows = executor.execute(pending.definition(), pending.date(), execution);
                store.finish(pending.id(), "SUCCESS", rows, "更新完成");
            } catch (Exception e) {
                String message = execution.stopped() ? execution.reason() : error(e);
                if (pending.definition().mode() == UpdateDefinition.Mode.SCRIPT) message += "；脚本中已提交的 DDL 或外部副作用需检查后重跑";
                store.finish(pending.id(), execution.stopped() ? "CANCELLED" : "FAILED", 0, message);
            } finally { watchdog.cancel(false); active.remove(pending.id()); }
        } catch (Exception e) { LOG.warn("Data update worker failed for run {} ({})", pending.id(), e.getClass().getSimpleName()); }
        finally { dispatched.remove(pending.id()); }
    }
    static String error(Exception error) {
        if (error instanceof UpdateDefinition.ValidationException) return error.getMessage();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return "数据库执行失败，SQLState=" + sql.getSQLState() + "，错误码=" + sql.getErrorCode();
        }
        return "执行连接或配置异常（" + error.getClass().getSimpleName() + "）";
    }
    @PreDestroy public void close() {
        ready = false; active.values().forEach(run -> run.stop("服务正在关闭")); workers.shutdownNow(); monitor.shutdownNow();
    }
}
