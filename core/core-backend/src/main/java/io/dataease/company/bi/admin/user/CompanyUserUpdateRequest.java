package io.dataease.company.bi.admin.user;

public class CompanyUserUpdateRequest {
    private String feishuUserId;
    public String getFeishuUserId() { return feishuUserId; }
    public void setFeishuUserId(String value) { feishuUserId = value; }

    private String username;
    private String displayName;
    private String accountRole;

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
}
