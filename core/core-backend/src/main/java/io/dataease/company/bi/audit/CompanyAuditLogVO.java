package io.dataease.company.bi.audit;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

public class CompanyAuditLogVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorUserId;
    private String actionType;
    private String targetType;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long targetId;
    private String beforeJson;
    private String afterJson;
    private String ip;
    private String requestId;
    private Long createdAt;

    public static CompanyAuditLogVO from(CompanyBiAuditLog row) {
        if (row == null) {
            return null;
        }
        CompanyAuditLogVO vo = new CompanyAuditLogVO();
        vo.setId(row.getId());
        vo.setOperatorUserId(row.getOperatorUserId());
        vo.setActionType(row.getActionType());
        vo.setTargetType(row.getTargetType());
        vo.setTargetId(row.getTargetId());
        vo.setBeforeJson(row.getBeforeJson());
        vo.setAfterJson(row.getAfterJson());
        vo.setIp(row.getIp());
        vo.setRequestId(row.getRequestId());
        vo.setCreatedAt(row.getCreatedAt());
        return vo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOperatorUserId() {
        return operatorUserId;
    }

    public void setOperatorUserId(Long operatorUserId) {
        this.operatorUserId = operatorUserId;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public void setBeforeJson(String beforeJson) {
        this.beforeJson = beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public void setAfterJson(String afterJson) {
        this.afterJson = afterJson;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
