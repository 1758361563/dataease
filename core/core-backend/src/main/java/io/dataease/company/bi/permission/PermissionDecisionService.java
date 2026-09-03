package io.dataease.company.bi.permission;

import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.user.CompanyAccountRole;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class PermissionDecisionService {

    private final CompanyBiUserService userService;
    private final DatasetPermissionResolver datasetPermissionResolver;
    private final DashboardPermissionResolver dashboardPermissionResolver;
    private final DashboardPermissionProjectionService projectionService;
    private final CompanyExportPolicy exportPolicy;

    public PermissionDecisionService(CompanyBiUserService userService,
                                     DatasetPermissionResolver datasetPermissionResolver) {
        this(userService, datasetPermissionResolver, null, null, null);
    }

    @Autowired
    public PermissionDecisionService(CompanyBiUserService userService,
                                     DatasetPermissionResolver datasetPermissionResolver,
                                     DashboardPermissionResolver dashboardPermissionResolver,
                                     DashboardPermissionProjectionService projectionService,
                                     CompanyExportPolicy exportPolicy) {
        this.userService = userService;
        this.datasetPermissionResolver = datasetPermissionResolver;
        this.dashboardPermissionResolver = dashboardPermissionResolver;
        this.projectionService = projectionService;
        this.exportPolicy = exportPolicy;
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
        CompanyBiUser user = enabledUser(userId);
        if (user == null) {
            return false;
        }
        CompanyAccountRole role = roleOf(user);
        if (role == null) {
            return false;
        }
        if (capability == CompanyCapability.DASHBOARD_EXPORT) {
            return exportPolicy != null && exportPolicy.hasExportCapability(role);
        }
        return CompanyCapability.granted(role, capability);
    }

    public CompanyResourceRelation resolveDashboardRelation(Long userId, Long dashboardId) {
        if (dashboardPermissionResolver == null) {
            return CompanyResourceRelation.NONE;
        }
        CompanyBiUser user = enabledUser(userId);
        if (user == null) {
            return CompanyResourceRelation.NONE;
        }
        return dashboardPermissionResolver.resolveDashboardRelation(user, dashboardId);
    }

    public boolean hasDashboardAccess(Long userId, Long dashboardId) {
        return resolveDashboardRelation(userId, dashboardId).hasAccess();
    }

    public CompanyResourceRelation resolveFolderRelation(Long userId, Long folderId) {
        if (dashboardPermissionResolver == null) {
            return CompanyResourceRelation.NONE;
        }
        CompanyBiUser user = enabledUser(userId);
        if (user == null) {
            return CompanyResourceRelation.NONE;
        }
        return dashboardPermissionResolver.resolveFolderRelation(user, folderId);
    }

    public boolean hasFolderAccess(Long userId, Long folderId) {
        return resolveFolderRelation(userId, folderId).hasAccess();
    }

    public boolean isFolder(Long id) {
        return dashboardPermissionResolver != null && dashboardPermissionResolver.isFolder(id);
    }

    public VisualizationHierarchy hierarchy() {
        if (dashboardPermissionResolver == null) {
            return VisualizationHierarchy.empty();
        }
        return dashboardPermissionResolver.hierarchy();
    }

    public boolean canViewDashboard(Long userId, Long dashboardId) {
        return hasDashboardAccess(userId, dashboardId)
                && hasCapability(userId, CompanyCapability.DASHBOARD_VIEW);
    }

    public boolean canEditDashboard(Long userId, Long dashboardId) {
        return hasDashboardAccess(userId, dashboardId)
                && hasCapability(userId, CompanyCapability.DASHBOARD_EDIT);
    }

    public boolean canExportDashboard(Long userId, Long dashboardId) {
        return canExportDashboard(userId, dashboardId, null);
    }

    public boolean canExportDashboard(Long userId, Long dashboardId, CompanyExportKind kind) {
        if (!hasDashboardAccess(userId, dashboardId)) {
            return false;
        }
        CompanyBiUser user = enabledUser(userId);
        CompanyAccountRole role = roleOf(user);
        if (role == null) {
            return false;
        }
        if (kind == null) {
            return hasCapability(userId, CompanyCapability.DASHBOARD_EXPORT);
        }
        return exportPolicy != null && exportPolicy.allows(role, kind);
    }

    public boolean canCreateDashboard(Long userId) {
        return hasCapability(userId, CompanyCapability.DASHBOARD_EDIT);
    }

    public boolean canViewFolder(Long userId, Long folderId) {
        return hasFolderAccess(userId, folderId)
                && hasCapability(userId, CompanyCapability.DASHBOARD_VIEW);
    }

    public boolean canEditFolder(Long userId, Long folderId) {
        return hasFolderAccess(userId, folderId)
                && hasCapability(userId, CompanyCapability.DASHBOARD_EDIT);
    }

    public boolean canCreateInFolder(Long userId, Long pid) {
        if (!canCreateDashboard(userId)) {
            return false;
        }
        return VisualizationHierarchy.isRootPid(pid) || hasFolderAccess(userId, pid);
    }

    public DashboardPermissionProjection projectDashboard(Long userId, Long dashboardId) {
        if (projectionService == null) {
            return new DashboardPermissionProjection(0, 0);
        }
        CompanyBiUser user = enabledUser(userId);
        CompanyAccountRole role = roleOf(user);
        boolean access = hasDashboardAccess(userId, dashboardId);
        return projectionService.project(role, access);
    }

    public java.util.Set<Long> accessibleDashboardIds(Long userId) {
        if (dashboardPermissionResolver == null || enabledUser(userId) == null) {
            return java.util.Set.of();
        }
        return dashboardPermissionResolver.accessibleDashboardIds(userId);
    }

    public java.util.Set<Long> accessibleFolderIds(Long userId) {
        if (dashboardPermissionResolver == null || enabledUser(userId) == null) {
            return java.util.Set.of();
        }
        return dashboardPermissionResolver.accessibleFolderIds(userId);
    }

    public java.util.List<CompanyCapability> listCapabilities(Long userId) {
        java.util.List<CompanyCapability> result = new java.util.ArrayList<>();
        for (CompanyCapability capability : CompanyCapability.values()) {
            if (hasCapability(userId, capability)) {
                result.add(capability);
            }
        }
        return result;
    }

    private CompanyBiUser enabledUser(Long userId) {
        if (userId == null) {
            return null;
        }
        CompanyBiUser user = userService.findById(userId);
        if (user == null || !user.isEnabled()) {
            return null;
        }
        return user;
    }

    private static CompanyAccountRole roleOf(CompanyBiUser user) {
        if (user == null || user.getAccountRole() == null) {
            return null;
        }
        try {
            return CompanyAccountRole.valueOf(user.getAccountRole());
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
