package io.dataease.company.bi.permission;

import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.utils.AuthUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyDashboardMutationSupport {

    private final CompanyDashboardGuard dashboardGuard;
    private final CompanyDashboardFolderSupport folderSupport;
    private final CompanyDashboardAclBootstrapper aclBootstrapper;

    public CompanyDashboardMutationSupport(CompanyDashboardGuard dashboardGuard,
                                           CompanyDashboardFolderSupport folderSupport,
                                           CompanyDashboardAclBootstrapper aclBootstrapper) {
        this.dashboardGuard = dashboardGuard;
        this.folderSupport = folderSupport;
        this.aclBootstrapper = aclBootstrapper;
    }

    public void requireEditResource(Long id) {
        if (id == null) {
            CompanyDashboardGuard.deny();
        }
        if (folderSupport.isFolder(id)) {
            List<Long> leaves = folderSupport.descendantDashboardLeafIds(id);
            if (leaves.isEmpty()) {
                dashboardGuard.requireCreate();
                return;
            }
            for (Long leaf : leaves) {
                dashboardGuard.requireEdit(leaf);
            }
            return;
        }
        dashboardGuard.requireEdit(id);
    }

    public void afterCreate(Long dashboardId, String nodeType) {
        if (dashboardId == null || "folder".equals(nodeType)) {
            return;
        }
        Long userId = AuthUtils.getUser() == null ? null : AuthUtils.getUser().getUserId();
        aclBootstrapper.grantCreatorVisitor(userId, dashboardId);
    }
}
