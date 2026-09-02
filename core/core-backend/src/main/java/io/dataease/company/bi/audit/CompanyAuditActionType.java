package io.dataease.company.bi.audit;

public enum CompanyAuditActionType {
    USER_CREATE,
    USER_ENABLE,
    USER_DISABLE,
    USER_ROLE_CHANGE,
    USER_UPDATE,
    PASSWORD_RESET,
    GROUP_CREATE,
    GROUP_UPDATE,
    GROUP_STATUS_CHANGE,
    GROUP_MEMBER_ADD,
    GROUP_MEMBER_REMOVE,
    ACL_GRANT,
    ACL_CHANGE,
    ACL_REVOKE
}
