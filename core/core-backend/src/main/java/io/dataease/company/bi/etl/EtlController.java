package io.dataease.company.bi.etl;

import io.dataease.company.bi.admin.*;
import io.dataease.company.bi.audit.*;
import io.dataease.company.bi.permission.CompanyCapability;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import java.time.*;
import java.util.*;
import static io.dataease.company.bi.etl.EtlModel.*;

@RestController
@RequestMapping("/company/etl")
@ConditionalOnProperty(name="company.bi.auth.enabled",havingValue="true",matchIfMissing=true)
public class EtlController {
    private final CompanyAdminGuard guard;
    private final CompanyAuditRecorder audit;
    private final EtlStore store;
    private final EtlConnections connections;
    private final EtlSwitchService switches;
    private final EtlChainRunner runner;
    private final EtlQuartzRegistrar registrar;
    public EtlController(CompanyAdminGuard guard,CompanyAuditRecorder audit,EtlStore store,EtlConnections connections,
                         EtlSwitchService switches,EtlChainRunner runner,EtlQuartzRegistrar registrar) {
        this.guard=guard;this.audit=audit;this.store=store;this.connections=connections;this.switches=switches;this.runner=runner;this.registrar=registrar;
    }
    private long admin() { return guard.require(CompanyCapability.ETL_ACCESS); }
    private void record(HttpServletRequest request,long user,CompanyAuditActionType type,Long id,Object before,Object after) {
        audit.record(CompanyAdminAuditContext.from(request,user),type,"ETL",id,before==null ? null : json(before),after==null ? null : json(after));
    }
    @GetMapping("/state") public Map<String,Object> state() throws Exception {
        admin();Map<String,Object> result=new LinkedHashMap<>();
        result.put("setting",store.setting());result.put("instanceId",switches.instanceId());result.put("effective",switches.effective());
        result.put("reason",switches.reason());result.put("hardDisabled",switches.hardDisabled());result.put("nextFireTime",registrar.next());
        result.put("timezoneWarning",!ZoneId.systemDefault().equals(ZONE));result.put("busy",runner.busy());result.put("message",runner.lastMessage());
        result.put("tasks",store.tasks());result.put("datasources",store.datasources());
        Long id=store.setting().activePackageId();result.put("manifest",id==null ? null : store.load(id).manifest());
        Long testId=store.setting().activeTestPackageId();result.put("testManifest",testId==null ? null : store.load(testId).manifest());return result;
    }
    public record Settings(String dailyAt,boolean chainEnabled,Long refreshDatasourceId) {}
    @PostMapping("/settings") public void settings(@RequestBody Settings body,HttpServletRequest request) throws Exception {
        long user=admin();synchronized(runner) {
            require(!runner.busy(),"请等待当前批次结束后修改设置");
            if (body.refreshDatasourceId()!=null) require(store.datasources().stream().anyMatch(d -> body.refreshDatasourceId().toString().equals(d.get("id"))),"刷新数据源不存在");
            var before=store.setting();
            store.tx.executeWithoutResult(tx -> { store.configure(body.dailyAt(),body.chainEnabled(),body.refreshDatasourceId());record(request,user,CompanyAuditActionType.ETL_SETTINGS,1L,before,store.setting()); });
            registrar.sync();
        }
    }
    public record Toggle(boolean enabled,String confirmation) {}
    @PostMapping("/switch") public void toggle(@RequestBody Toggle body,HttpServletRequest request) throws Exception {
        long user=admin();
        synchronized(runner) {
        if (body.enabled()) {
            require(!runner.busy(),"请等待当前批次结束后启用正式刷新");
            require("旧刷新程序已停止".equals(body.confirmation()),"请输入确认语：旧刷新程序已停止");
            require(!switches.hardDisabled() && switches.instanceId()!=null,"请先配置实例 ID 并解除运维强制关闭");
            require(store.setting().activePackageId()!=null && store.setting().refreshDatasourceId()!=null,"请先配置刷新数据源并激活正式包");
        }
        var before=store.setting();
        store.tx.executeWithoutResult(tx -> { store.setSwitch(body.enabled(),switches.instanceId(),user);record(request,user,CompanyAuditActionType.ETL_SWITCH,1L,before,store.setting()); });
        registrar.sync();
        if (body.enabled()) runner.resumeOnStartup();
        }
    }
    @PostMapping("/tasks/{name}") public void task(@PathVariable String name,@RequestBody Toggle body,HttpServletRequest request) {
        long user=admin();store.tx.executeWithoutResult(tx -> { store.task(name,body.enabled());record(request,user,CompanyAuditActionType.ETL_TASK,null,null,Map.of("task",name,"enabled",body.enabled())); });
    }
    @GetMapping("/packages") public List<Map<String,Object>> packages() { admin();return store.packages(); }
    @GetMapping("/packages/{id}/files") public List<Map<String,Object>> files(@PathVariable long id) { admin();return store.files(id); }
    @PostMapping("/packages") public Map<String,Object> upload(@RequestParam("file") MultipartFile file,HttpServletRequest request) throws Exception {
        long user=admin();require(file.getSize()<=EtlPackageValidator.MAX_BYTES,"SQL 包超过 16 MB");
        byte[] bytes=file.getBytes();Map<String,String> files=Map.of();Manifest manifest=null;String rejection=null;
        try { files=EtlPackageValidator.unpack(bytes);manifest=EtlPackageValidator.validate(files); }
        catch (IllegalArgumentException e) { rejection=e.getMessage(); }
        catch (Exception e) { rejection="ZIP 结构或 UTF-8 编码无效"; }
        // Parsing exceptions never include source text. Keep only messages produced by our validators.
        if (rejection!=null && rejection.length()>200) rejection="SQL 包校验失败";
        final Map<String,String> savedFiles=files;final Manifest savedManifest=manifest;final String reason=rejection;
        long id=store.tx.execute(tx -> {
            long value=store.savePackage(bytes,savedFiles,savedManifest,reason,user);
            record(request,user,CompanyAuditActionType.ETL_IMPORT,value,null,Map.of("sha256",EtlPackageValidator.hash(bytes),"status",reason==null ? "imported" : "rejected"));return value;
        });
        return Map.of("id",id,"status",rejection==null ? "imported" : "rejected","reason",rejection==null ? "" : rejection);
    }
    @PostMapping("/packages/{id}/activate") public void activate(@PathVariable long id,HttpServletRequest request) {
        long user=admin();store.tx.executeWithoutResult(tx -> { store.activate(id);record(request,user,CompanyAuditActionType.ETL_ACTIVATE,id,null,Map.of("packageId",id)); });
    }
    @PostMapping("/packages/{id}/initialize") public void initialize(@PathVariable long id,@RequestBody Toggle body,HttpServletRequest request) {
        long user=admin();require("初始化测试表".equals(body.confirmation()),"请输入确认语：初始化测试表");
        record(request,user,CompanyAuditActionType.ETL_INITIALIZE,id,null,Map.of("packageId",id));runner.initializeTest(id);
    }
    public record Run(String kind,String fromTask,boolean single) {}
    @PostMapping("/run") public Map<String,String> run(@RequestBody Run body,HttpServletRequest request) {
        long user=admin();
        record(request,user,CompanyAuditActionType.ETL_RUN,null,null,body);
        return Map.of("plannedAt",runner.submit(body.kind(),body.fromTask(),body.single(),Instant.now(),false));
    }
    @GetMapping("/runs") public List<Map<String,Object>> runs(@RequestParam(defaultValue="refresh") String kind,
            @RequestParam(defaultValue="") String task,@RequestParam(defaultValue="") String from,@RequestParam(defaultValue="") String to) throws Exception {
        admin();require(Set.of("refresh","refresh_test").contains(kind),"运行类型无效");
        require(task.isEmpty() || EtlPackageValidator.NAMES.contains(task),"任务无效");
        LocalDate end=to.isEmpty() ? LocalDate.now(ZONE).plusDays(1) : LocalDate.parse(to).plusDays(1);
        LocalDate start=from.isEmpty() ? end.minusDays(31) : LocalDate.parse(from);
        Long datasource=store.setting().refreshDatasourceId();if (datasource==null) return List.of();
        try (var handle=connections.open(datasource)) {
            return new EtlRunLogRepository(handle.connection(),Clock.systemUTC()).list(kind,task,start.toString(),end.toString(),500);
        }
    }
    @GetMapping("/latest") public List<Map<String,Object>> latest() throws Exception {
        admin();Long datasource=store.setting().refreshDatasourceId();if(datasource==null) return List.of();
        try(var handle=connections.open(datasource)) {
            var logs=new EtlRunLogRepository(handle.connection(),Clock.systemUTC());
            List<Map<String,Object>> rows=new ArrayList<>();
            for(String name:EtlPackageValidator.NAMES) rows.addAll(logs.list("refresh",name,"1900-01-01","2999-01-01",1));
            return rows;
        }
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,Object>> failure(Exception e) throws Exception {
        if (e instanceof io.dataease.exception.DEException) throw e;
        String message=e instanceof IllegalArgumentException && !(e instanceof NumberFormatException) ? e.getMessage() : error(e);
        if (message==null || message.length()>200) message="请求参数无效";
        return ResponseEntity.badRequest().body(Map.of("code",400,"msg",message));
    }
}
