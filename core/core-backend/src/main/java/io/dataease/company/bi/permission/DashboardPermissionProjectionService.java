package io.dataease.company.bi.permission;

import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.user.CompanyAccountRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class DashboardPermissionProjectionService {

    public static final int FOLDER_SHELL_WEIGHT = 1;

    private final CompanyExportPolicy exportPolicy;

    public DashboardPermissionProjectionService(CompanyExportPolicy exportPolicy) {
        this.exportPolicy = exportPolicy;
    }

    public DashboardPermissionProjection project(CompanyAccountRole role, boolean dashboardAccess) {
        if (!dashboardAccess || role == null) {
            return new DashboardPermissionProjection(0, 0);
        }
        return switch (role) {
            case ADMIN -> new DashboardPermissionProjection(9, 0);
            case NORMAL -> new DashboardPermissionProjection(6, exportPolicy.extFor(role));
            case READONLY -> new DashboardPermissionProjection(1, 0);
        };
    }

    public DashboardPermissionProjection folderShell() {
        return new DashboardPermissionProjection(FOLDER_SHELL_WEIGHT, 0);
    }

    public DashboardPermissionProjection authorizedFolder(CompanyAccountRole role) {
        if (role == CompanyAccountRole.ADMIN) {
            return new DashboardPermissionProjection(9, 0);
        }
        return new DashboardPermissionProjection(FOLDER_SHELL_WEIGHT, 0);
    }
}
