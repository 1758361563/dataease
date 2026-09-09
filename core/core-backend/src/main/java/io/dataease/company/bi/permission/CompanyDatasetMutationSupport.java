package io.dataease.company.bi.permission;

import io.dataease.company.bi.acl.CompanyAclRepository;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.utils.AuthUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyDatasetMutationSupport {

    private final CompanyAclRepository aclRepository;

    public CompanyDatasetMutationSupport(CompanyAclRepository aclRepository) {
        this.aclRepository = aclRepository;
    }

    public void afterCreate(Long resourceId, String nodeType) {
        if (resourceId == null || !"dataset".equalsIgnoreCase(nodeType)) {
            return;
        }
        Long userId = AuthUtils.getUser() == null ? null : AuthUtils.getUser().getUserId();
        if (userId == null) {
            return;
        }
        aclRepository.insertIfAbsentUserDatasetRead(userId, resourceId, userId);
    }
}
