package io.dataease.company.bi.audit;

import io.dataease.company.bi.admin.CompanyAdminAuditContext;

public interface CompanyAuditRecorder {

    void record(CompanyAdminAuditContext context, CompanyAuditActionType actionType, String targetType, Long targetId,
                String beforeJson, String afterJson);
}
