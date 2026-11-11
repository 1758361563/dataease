package io.dataease.company.bi.feishu;

import io.dataease.company.bi.admin.CompanyAdminGuard;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.permission.CompanyCapability;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class FeishuIdentityController {
    private final CompanyAdminGuard guard;
    private final CompanyFeishuIdentityService identities;
    private final FeishuLoginSettings settings;
    public FeishuIdentityController(CompanyAdminGuard guard,
                                    CompanyFeishuIdentityService identities, FeishuLoginSettings settings) {
        this.guard=guard; this.identities=identities; this.settings=settings;
    }
    @GetMapping("/company/bi/admin/users/feishu/search")
    public Map<String,Object> search(@RequestParam String query) {
        guard.require(CompanyCapability.USER_MANAGE);
        return identities.search(query);
    }
    @GetMapping("/company/bi/feishu/bound-users")
    public List<Map<String,Object>> recipients() {
        guard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return identities.boundRecipients();
    }
    @GetMapping("/company/bi/feishu/login-settings")
    public Map<String,Object> get() { guard.requireBootstrapAdmin(); return settings.get(); }
    @PostMapping("/company/bi/feishu/login-settings")
    public Map<String,Object> save(@RequestBody Map<String,Object> body) { return settings.save(body,guard.requireBootstrapAdmin()); }
}
