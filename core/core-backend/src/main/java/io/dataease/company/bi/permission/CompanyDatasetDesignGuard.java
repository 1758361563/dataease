package io.dataease.company.bi.permission;

import io.dataease.auth.bo.TokenUserBO;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import io.dataease.utils.AuthUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyDatasetDesignGuard {

    private final PermissionDecisionService permissionDecisionService;

    public CompanyDatasetDesignGuard(PermissionDecisionService permissionDecisionService) {
        this.permissionDecisionService = permissionDecisionService;
    }

    public void requireDatasetDesign() {
        TokenUserBO user = AuthUtils.getUser();
        if (user == null || user.getUserId() == null) {
            deny();
        }
        if (!permissionDecisionService.hasCapability(user.getUserId(), CompanyCapability.DATASET_DESIGN)) {
            deny();
        }
    }

    private static void deny() {
        DEException.throwException(ResultCode.PERMISSION_NO_ACCESS.code(), ResultCode.PERMISSION_NO_ACCESS.message());
    }
}
