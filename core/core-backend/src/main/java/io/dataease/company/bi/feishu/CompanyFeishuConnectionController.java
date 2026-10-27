package io.dataease.company.bi.feishu;

import io.dataease.company.bi.admin.CompanyAdminGuard;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.permission.CompanyCapability;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/company/bi/feishu/connection")
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyFeishuConnectionController {
    private final CompanyAdminGuard guard;
    private final CompanyFeishuConnectionService connections;
    private final CompanyFeishuWorker worker;
    public CompanyFeishuConnectionController(CompanyAdminGuard guard, CompanyFeishuConnectionService connections, CompanyFeishuWorker worker) {
        this.guard = guard; this.connections = connections; this.worker = worker;
    }
    @GetMapping public Map<String, Object> get() {
        guard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return connections.get();
    }
    @PostMapping public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        long operator = guard.requireBootstrapAdmin();
        return connections.save(body, operator);
    }
    @GetMapping("/runtime") public Map<String, Object> runtime() {
        guard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return worker.status();
    }
    @PostMapping("/test") public Map<String, Object> test() {
        guard.requireBootstrapAdmin();
        return worker.call("test", Map.of());
    }
}
