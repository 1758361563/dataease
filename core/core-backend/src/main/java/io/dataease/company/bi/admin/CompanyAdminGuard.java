package io.dataease.company.bi.admin;

import io.dataease.auth.bo.TokenUserBO;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.permission.CompanyCapability;
import io.dataease.company.bi.permission.PermissionDecisionService;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.utils.AuthUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyAdminGuard {

    private final PermissionDecisionService permissionDecisionService;

    public CompanyAdminGuard(PermissionDecisionService permissionDecisionService) {
        this.permissionDecisionService = permissionDecisionService;
    }

    public long require(CompanyCapability capability) {
        TokenUserBO user = AuthUtils.getUser();
        if (user == null || user.getUserId() == null) {
            CompanyAdminErrors.deny();
        }
        if (!permissionDecisionService.hasCapability(user.getUserId(), capability)) {
            CompanyAdminErrors.deny();
        }
        return user.getUserId();
    }

    public long requireBootstrapAdmin() {
        TokenUserBO user = AuthUtils.getUser();
        if (user == null || user.getUserId() == null) {
            CompanyAdminErrors.deny();
        }
        if (user.getUserId() != CompanyBiUserService.BOOTSTRAP_ADMIN_ID) {
            CompanyAdminErrors.deny();
        }
        if (!permissionDecisionService.hasCapability(user.getUserId(), CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE)) {
            CompanyAdminErrors.deny();
        }
        return user.getUserId();
    }
}
