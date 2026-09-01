package io.dataease.company.bi.permission;

import io.dataease.company.bi.acl.CompanyAclRepository;
import io.dataease.company.bi.acl.CompanySubjectType;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.group.CompanyGroupRepository;
import io.dataease.company.bi.user.CompanyBiUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class DatasetPermissionResolver {

    private final CompanyAclRepository aclRepository;
    private final CompanyGroupRepository groupRepository;

    public DatasetPermissionResolver(CompanyAclRepository aclRepository, CompanyGroupRepository groupRepository) {
        this.aclRepository = aclRepository;
        this.groupRepository = groupRepository;
    }

    public boolean hasRead(CompanyBiUser user, Long datasetId) {
        if (user == null || !user.isEnabled() || datasetId == null) {
            return false;
        }
        if (aclRepository.existsDatasetAcl(CompanySubjectType.USER, user.getId(), datasetId)) {
            return true;
        }
        List<Long> groupIds = groupRepository.listEnabledGroupIds(user.getId());
        return aclRepository.existsDatasetAclForGroups(groupIds, datasetId);
    }
}
