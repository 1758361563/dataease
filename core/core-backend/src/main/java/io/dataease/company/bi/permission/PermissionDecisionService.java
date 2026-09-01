package io.dataease.company.bi.permission;

import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.user.CompanyAccountRole;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class PermissionDecisionService {

    private final CompanyBiUserService userService;
    private final DatasetPermissionResolver datasetPermissionResolver;

    public PermissionDecisionService(CompanyBiUserService userService,
                                     DatasetPermissionResolver datasetPermissionResolver) {
        this.userService = userService;
        this.datasetPermissionResolver = datasetPermissionResolver;
    }

    public boolean hasDatasetRead(Long userId, Long datasetId) {
        if (userId == null || datasetId == null) {
            return false;
        }
        CompanyBiUser user = userService.findById(userId);
        if (user == null || !user.isEnabled()) {
            return false;
        }
        return datasetPermissionResolver.hasRead(user, datasetId);
    }

    public boolean hasCapability(Long userId, CompanyCapability capability) {
        if (userId == null || capability == null) {
            return false;
        }
        CompanyBiUser user = userService.findById(userId);
        if (user == null || !user.isEnabled()) {
            return false;
        }
        CompanyAccountRole role;
        try {
            role = CompanyAccountRole.valueOf(user.getAccountRole());
        } catch (RuntimeException ignored) {
            return false;
        }
        return CompanyCapability.granted(role, capability);
    }
}
