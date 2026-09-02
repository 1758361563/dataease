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
public class CompanyDashboardGuard {

    private final PermissionDecisionService permissionDecisionService;

    public CompanyDashboardGuard(PermissionDecisionService permissionDecisionService) {
        this.permissionDecisionService = permissionDecisionService;
    }

    public void requireView(Long resourceId) {
        Long userId = currentUserId();
        if (permissionDecisionService.isFolder(resourceId)) {
            if (!permissionDecisionService.canViewFolder(userId, resourceId)) {
                deny();
            }
            return;
        }
        if (!permissionDecisionService.canViewDashboard(userId, resourceId)) {
            deny();
        }
    }

    public void requireEdit(Long dashboardId) {
        Long userId = currentUserId();
        if (!permissionDecisionService.canEditDashboard(userId, dashboardId)) {
            deny();
        }
    }

    public void requireFolderAccess(Long folderId) {
        Long userId = currentUserId();
        if (!permissionDecisionService.hasFolderAccess(userId, folderId)) {
            deny();
        }
    }

    public void requireFolderEdit(Long folderId) {
        Long userId = currentUserId();
        if (!permissionDecisionService.canEditFolder(userId, folderId)) {
            deny();
        }
    }

    public void requireCreate() {
        Long userId = currentUserId();
        if (!permissionDecisionService.canCreateDashboard(userId)) {
            deny();
        }
    }

    public void requireCreateIn(Long pid) {
        Long userId = currentUserId();
        if (!permissionDecisionService.canCreateInFolder(userId, pid)) {
            deny();
        }
    }

    public void requireExport(Long dashboardId, CompanyExportKind kind) {
        Long userId = currentUserId();
        if (!permissionDecisionService.canExportDashboard(userId, dashboardId, kind)) {
            deny();
        }
    }

    public Long currentUserId() {
        TokenUserBO user = AuthUtils.getUser();
        if (user == null || user.getUserId() == null) {
            deny();
        }
        return AuthUtils.getUser().getUserId();
    }

    public static void deny() {
        DEException.throwException(ResultCode.PERMISSION_NO_ACCESS.code(), ResultCode.PERMISSION_NO_ACCESS.message());
    }
}
