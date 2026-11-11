package io.dataease.company.bi.admin.user;

public class CompanyUserCreateRequest {
    private java.util.List<Long> groupIds;
    public java.util.List<Long> getGroupIds() { return groupIds; }
    public void setGroupIds(java.util.List<Long> value) { groupIds = value; }

    private String feishuUserId;
    public String getFeishuUserId() { return feishuUserId; }
    public void setFeishuUserId(String value) { feishuUserId = value; }

    private String username;
    private String displayName;
    private String accountRole;
    private String status;
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAccountRole() {
        return accountRole;
    }

    public void setAccountRole(String accountRole) {
        this.accountRole = accountRole;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
