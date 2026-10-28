package io.dataease.company.bi.etl;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static io.dataease.company.bi.etl.EtlModel.*;

@Component
@ConditionalOnProperty(name="company.bi.auth.enabled", havingValue="true", matchIfMissing=true)
public class EtlChainRunner {
    private final EtlStore store;
    private final EtlConnections connections;
    private final EtlSwitchService switches;
    private final Clock clock;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r,"company-etl-runner");t.setDaemon(true);return t; });
    private volatile String lastMessage = "尚未执行";
    @org.springframework.beans.factory.annotation.Autowired
    public EtlChainRunner(EtlStore store, EtlConnections connections, EtlSwitchService switches) {
        this(store,connections,switches,Clock.systemUTC());
    }
    EtlChainRunner(EtlStore store, EtlConnections connections, EtlSwitchService switches, Clock clock) {
        this.store=store;this.connections=connections;this.switches=switches;this.clock=clock;
    }
    public boolean busy() { return busy.get(); }
    public String lastMessage() { return lastMessage; }
    public synchronized String submit(String kind, String fromTask, boolean single, Instant planned, boolean schedule) {
        switches.allow(kind,clock.instant());
        EtlStore.Setting setting = store.setting();
        require(!schedule || setting.chainEnabled(), "整条链已暂停");
        Long packageId = "refresh".equals(kind) ? setting.activePackageId() : setting.activeTestPackageId();
        require(packageId != null && setting.refreshDatasourceId() != null, "请先配置刷新数据源并激活 SQL 包");
        SqlPackage pack = store.load(packageId);
        List<String> names = selectTasks(store.enabledTasks(),fromTask,single);
        require(!names.isEmpty(), "没有启用的任务");
        Window window = Window.at(planned);
        String slot = time(schedule ? planned.truncatedTo(java.time.temporal.ChronoUnit.MINUTES) : planned);
        EtlStore.Batch batch = new EtlStore.Batch(slot,kind,packageId,setting.refreshDatasourceId(),window.start(),window.end(),names,schedule ? "schedule" : "manual",single);
        enqueue(() -> run(batch,false));
        return slot;
    }
    static List<String> selectTasks(Set<String> enabled, String fromTask, boolean single) {
        require(fromTask == null || EtlPackageValidator.NAMES.contains(fromTask), "起始任务无效");
        require(!single || fromTask != null, "单任务运行需要选择任务");
        int start = fromTask == null ? 0 : EtlPackageValidator.NAMES.indexOf(fromTask);
        return EtlPackageValidator.NAMES.subList(start,single ? start+1 : 3).stream().filter(enabled::contains).toList();
    }
    private void enqueue(Checked work) {
        require(busy.compareAndSet(false,true), "已有批次正在执行");
        try {
            worker.execute(() -> {
                lastMessage="正在执行";
                try { work.run(); }
                catch (Exception e) { lastMessage=error(e); }
                finally { busy.set(false); }
            });
        } catch (RuntimeException e) { busy.set(false);throw e; }
    }
    @FunctionalInterface interface Checked { void run() throws Exception; }
    void run(EtlStore.Batch request, boolean resume) throws Exception {
        switches.allow(request.kind(),clock.instant());
        try (EtlLock lock = new EtlLock(connections.open(request.datasourceId()))) {
            if (!lock.acquired()) { lastMessage="另一个批次持有刷新锁，本次未执行";return; }
            EtlStore.Batch batch = store.pin(request);
            require(batch.datasourceId() == request.datasourceId(), "批次绑定了其他数据源");
            executeBatch(batch,lock,resume);
        }
    }
    private void executeBatch(EtlStore.Batch batch, EtlLock lock, boolean resume) throws Exception {
        switches.allow(batch.kind(),clock.instant());
        SqlPackage pack = store.load(batch.packageId());
        require(pack.manifest().runKind().equals(batch.kind()), "批次类型与 SQL 包不一致");
        try (var handle = connections.open(batch.datasourceId())) {
            Connection connection = handle.connection();connection.setCatalog("qiwei_new_bi");EtlSqlScript.prepare(connection);
            EtlRunLogRepository logs = new EtlRunLogRepository(connection,clock);
            for (Task task : pack.manifest().tasks()) {
                if (!batch.names().contains(task.etlName())) continue;
                lock.check();
                switches.allow(batch.kind(),clock.instant());
                EtlRunLogRepository.Row existing = logs.find(task.etlName(),batch.kind(),batch.plannedAt());
                if (existing != null && !"running".equals(existing.status())) { connection.commit();continue; }
                // Only startup recovery may execute an existing running record.
                if (existing != null && !resume) { connection.commit();lastMessage="批次已被认领，等待原执行或启动恢复";return; }
                var claim = logs.claim(task.etlName(),batch);
                if (!claim.created() && !resume) { connection.commit();lastMessage="批次已被认领，等待原执行或启动恢复";return; }
                var row = claim.row();
                if (!"running".equals(row.status())) { connection.commit();continue; }
                require(row.windowStart().equals(batch.windowStart()) && row.windowEnd().equals(batch.windowEnd()) && row.triggerType().equals(batch.triggerType()), "运行记录与批次配置不一致");
                List<String> missing = new ArrayList<>();List<Long> used = new ArrayList<>();
                for (String dep : task.dependsOn()) {
                    if (batch.single() && "manual".equals(batch.triggerType())) {
                        Long id = logs.manualUpstream(dep,batch.kind(),batch.windowEnd());
                        if (id == null) missing.add(dep);else used.add(id);
                    } else {
                        var upstream = logs.find(dep,batch.kind(),batch.plannedAt());
                        if (upstream == null || !"success".equals(upstream.status())) missing.add(dep);
                    }
                }
                if (!missing.isEmpty()) { logs.finish(row.id(),"blocked",null,"上游未成功：" + String.join("、",missing));continue; }
                Instant started = clock.instant();
                Window window = new Window(batch.windowStart(),batch.windowEnd());
                try {
                    String sql = EtlSqlScript.render(pack.files().get(task.sql()),window);
                    var before = EtlAcceptance.measure(connection,task,window);connection.commit();
                    EtlSqlScript.execute(connection,sql,lock::check,lock::active);
                    var after = EtlAcceptance.measure(connection,task,window);connection.commit();
                    List<String> issues = EtlAcceptance.issues(before,after,task,window);
                    lock.check();
                    if (!issues.isEmpty()) logs.finish(row.id(),"fail",null,"提交后验收失败：" + String.join("；",issues));
                    else logs.finish(row.id(),"success",after.rows(),"35 天窗口刷新并验收通过，耗时 " + Duration.between(started,clock.instant()).toSeconds()
                            + " 秒；版本=" + pack.id() + (used.isEmpty() ? "" : "；上游记录=" + used));
                } catch (Exception e) {
                    EtlSqlScript.rollback(connection);
                    lock.check();
                    logs.finish(row.id(),"fail",null,error(e));
                }
            }
            // A second pass inspects terminal records without executing SQL again.
            boolean failed = false;
            for (String name : batch.names()) {
                var row = logs.find(name,batch.kind(),batch.plannedAt());
                if (row == null || "running".equals(row.status())) { lastMessage="批次仍有未结束记录，请检查运行记录";return; }
                failed |= !"success".equals(row.status());
            }
            connection.commit();lastMessage=failed ? "批次结束，存在失败或阻塞任务" : "批次完成，验收通过";
        }
    }
    public synchronized void resumeOnStartup() {
        if (switches.hardDisabled() || store.setting().refreshDatasourceId() == null) return;
        enqueue(() -> {
            long datasource = store.setting().refreshDatasourceId();
            try (EtlLock lock = new EtlLock(connections.open(datasource))) {
                if (!lock.acquired()) { lastMessage="启动恢复未取得锁";return; }
                try (var handle = connections.open(datasource)) {
                    EtlSqlScript.prepare(handle.connection());
                    var logs = new EtlRunLogRepository(handle.connection(),clock);
                    logs.interruptManual();
                    store.db.update("UPDATE company_etl_package SET init_status='fail' WHERE init_status='running'");
                    if (!switches.effective()) { lastMessage="手动中断记录已处理，正式续跑等待开关生效";return; }
                    for (String planned : logs.resumable()) {
                        var batch = store.batch(planned,"refresh");
                        if (batch == null) { lastMessage="发现无版本快照的历史 running 记录，需核对原执行器";continue; }
                        if (batch.datasourceId() == datasource) executeBatch(batch,lock,true);
                    }
                }
            }
        });
    }
    public synchronized void initializeTest(long id) {
        switches.allow("refresh_test",clock.instant());
        SqlPackage pack = store.load(id);
        require("test".equals(pack.manifest().packageKind()) && !pack.manifest().init().isEmpty(), "该包没有测试初始化脚本");
        Long datasource = store.setting().refreshDatasourceId();require(datasource != null,"请配置刷新数据源");
        enqueue(() -> {
            switches.allow("refresh_test",clock.instant());
            try (EtlLock lock = new EtlLock(connections.open(datasource))) {
                if (!lock.acquired()) { lastMessage="另一个批次持有刷新锁，本次未执行";return; }
                require(store.beginInit(id),"此包已执行过初始化，请查看初始化状态");
                boolean ok=false;
                try (var handle=connections.open(datasource)) {
                    handle.connection().setCatalog("qiwei_new_bi");EtlSqlScript.prepare(handle.connection());
                    for (Init init : pack.manifest().init()) EtlSqlScript.execute(handle.connection(),pack.files().get(init.sql()),lock::check,lock::active);
                    ok=true;lastMessage="测试表初始化完成";
                } finally { store.endInit(id,ok); }
            }
        });
    }
    @PreDestroy public void stop() { worker.shutdownNow(); }
}
