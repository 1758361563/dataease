package io.dataease.company.bi.permission;

import io.dataease.company.bi.acl.CompanyAclRepository;
import io.dataease.company.bi.acl.CompanyBiResourceAcl;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.group.CompanyGroupRepository;
import io.dataease.company.bi.user.CompanyBiUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class DashboardPermissionResolver {

    private final CompanyAclRepository aclRepository;
    private final CompanyGroupRepository groupRepository;

    public DashboardPermissionResolver(CompanyAclRepository aclRepository, CompanyGroupRepository groupRepository) {
        this.aclRepository = aclRepository;
        this.groupRepository = groupRepository;
    }

    public CompanyResourceRelation resolveDashboardRelation(CompanyBiUser user, Long dashboardId) {
        if (user == null || !user.isEnabled() || dashboardId == null) {
            return CompanyResourceRelation.NONE;
        }
        Map<Long, CompanyResourceRelation> relations = mapAccessibleRelations(user.getId());
        return relations.getOrDefault(dashboardId, CompanyResourceRelation.NONE);
    }

    public boolean hasDashboardAccess(CompanyBiUser user, Long dashboardId) {
        return resolveDashboardRelation(user, dashboardId).hasAccess();
    }

    public Map<Long, CompanyResourceRelation> mapAccessibleRelations(Long userId) {
        if (userId == null) {
            return Map.of();
        }
        List<Long> groupIds = groupRepository.listEnabledGroupIds(userId);
        List<CompanyBiResourceAcl> rows = aclRepository.listDashboardAcls(userId, groupIds);
        Map<Long, CompanyResourceRelation> merged = new HashMap<>();
        for (CompanyBiResourceAcl row : rows) {
            if (row == null || row.getResourceId() == null) {
                continue;
            }
            CompanyResourceRelation next = CompanyResourceRelation.fromLevel(row.getRelationLevel());
            merged.merge(row.getResourceId(), next, CompanyResourceRelation::max);
        }
        return merged;
    }

    public Set<Long> accessibleDashboardIds(Long userId) {
        return mapAccessibleRelations(userId).keySet();
    }
}
