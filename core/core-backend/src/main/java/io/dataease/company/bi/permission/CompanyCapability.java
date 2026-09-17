package io.dataease.company.bi.permission;

import io.dataease.company.bi.user.CompanyAccountRole;

public enum CompanyCapability {
    DASHBOARD_VIEW,
    DASHBOARD_EDIT,
    DASHBOARD_EXPORT,
    DASHBOARD_GRANT,
    DATASET_DESIGN,
    DATASOURCE_ACCESS,
    ETL_ACCESS,
    USER_MANAGE,
    GROUP_MANAGE,
    ACL_MANAGE,
    AUDIT_VIEW,
    FEISHU_SUBSCRIPTION_MANAGE;

    public static boolean granted(CompanyAccountRole role, CompanyCapability capability) {
        if (role == null || capability == null) {
            return false;
        }
        if (role == CompanyAccountRole.ADMIN) {
            return true;
        }
        return capability == DASHBOARD_VIEW;
    }
}
