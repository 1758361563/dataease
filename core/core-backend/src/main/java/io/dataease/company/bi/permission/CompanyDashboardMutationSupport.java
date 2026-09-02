package io.dataease.company.bi.permission;

import io.dataease.company.bi.acl.CompanyAclRepository;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.utils.AuthUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyDashboardMutationSupport {

    private final CompanyDashboardGuard dashboardGuard;
    private final PermissionDecisionService decisionService;
    private final CompanyAclRepository aclRepository;

    public CompanyDashboardMutationSupport(CompanyDashboardGuard dashboardGuard,
                                           PermissionDecisionService decisionService,
                                           CompanyAclRepository aclRepository) {
        this.dashboardGuard = dashboardGuard;
        this.decisionService = decisionService;
        this.aclRepository = aclRepository;
    }

    public void requireEditResource(Long id) {
        if (id == null) {
            CompanyDashboardGuard.deny();
        }
        if (decisionService.isFolder(id)) {
            dashboardGuard.requireFolderEdit(id);
            return;
        }
        dashboardGuard.requireEdit(id);
    }

    public void requireCreateIn(Long pid) {
        dashboardGuard.requireCreateIn(pid);
    }

    public void requireMove(Long id, Long targetPid) {
        requireEditResource(id);
        dashboardGuard.requireCreateIn(targetPid);
        if (decisionService.isFolder(id) && decisionService.hierarchy().wouldCreateCycle(id, targetPid)) {
            CompanyDashboardGuard.deny();
        }
    }

    public void requireCopy(Long sourceId, Long targetPid) {
        requireEditResource(sourceId);
        if (!VisualizationHierarchy.isRootPid(targetPid)) {
            dashboardGuard.requireFolderAccess(targetPid);
        }
    }

    public void afterCreate(Long resourceId, String nodeType) {
        if (resourceId == null) {
            return;
        }
        Long userId = AuthUtils.getUser() == null ? null : AuthUtils.getUser().getUserId();
        if (userId == null) {
            return;
        }
        if ("folder".equals(nodeType)) {
            aclRepository.insertIfAbsentUserFolderVisitor(userId, resourceId, userId);
            return;
        }
        aclRepository.insertIfAbsentUserDashboardVisitor(userId, resourceId, userId);
    }

    public Set<Long> subtreeResourceIds(Long id) {
        VisualizationHierarchy hierarchy = decisionService.hierarchy();
        Set<Long> ids = hierarchy.subtreeIds(id);
        if (ids.isEmpty() && id != null) {
            ids = Set.of(id);
        }
        return ids;
    }

    public void cleanupAcls(Set<Long> resourceIds) {
        aclRepository.deleteAclsForResources(resourceIds);
    }
}
