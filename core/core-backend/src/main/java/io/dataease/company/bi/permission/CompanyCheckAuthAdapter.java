package io.dataease.company.bi.permission;

import io.dataease.api.permissions.auth.dto.BusiPerCheckDTO;
import io.dataease.auth.bo.TokenUserBO;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.utils.AuthUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyCheckAuthAdapter {

    private final CompanyResourceTypeLookup resourceTypeLookup;
    private final PermissionDecisionService permissionDecisionService;

    public CompanyCheckAuthAdapter(CompanyResourceTypeLookup resourceTypeLookup,
                                   PermissionDecisionService permissionDecisionService) {
        this.resourceTypeLookup = resourceTypeLookup;
        this.permissionDecisionService = permissionDecisionService;
    }

    public boolean checkAuth(BusiPerCheckDTO dto) {
        if (dto == null || dto.getId() == null) {
            return false;
        }
        CompanyResourceTypeLookup.Kind kind = resourceTypeLookup.identify(dto.getId());
        if (kind == CompanyResourceTypeLookup.Kind.DATASET) {
            TokenUserBO user = AuthUtils.getUser();
            if (user == null || user.getUserId() == null) {
                return false;
            }
            return permissionDecisionService.hasDatasetRead(user.getUserId(), dto.getId());
        }
        if (kind == CompanyResourceTypeLookup.Kind.DATASOURCE) {
            return true;
        }
        return false;
    }
}
