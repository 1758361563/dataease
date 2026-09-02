package io.dataease.company.bi.admin.user;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.dataease.company.bi.user.CompanyAccountRole;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyUserStatus;

import java.util.ArrayList;
import java.util.List;

public class CompanyUserVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String username;
    private String displayName;
    private String accountRole;
    private String status;
    private Long createdAt;
    private Long updatedAt;
    private Long lastLoginAt;
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<Long> groupIds = new ArrayList<>();

    public static CompanyUserVO from(CompanyBiUser user) {
        if (user == null) {
            return null;
        }
        CompanyUserVO vo = new CompanyUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setDisplayName(user.getDisplayName());
        vo.setAccountRole(user.getAccountRole());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        vo.setUpdatedAt(user.getUpdatedAt());
        vo.setLastLoginAt(user.getLastLoginAt());
        return vo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Long lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public List<Long> getGroupIds() {
        return groupIds;
    }

    public void setGroupIds(List<Long> groupIds) {
        this.groupIds = groupIds == null ? new ArrayList<>() : groupIds;
    }

    public CompanyAccountRole roleEnum() {
        try {
            return accountRole == null ? null : CompanyAccountRole.valueOf(accountRole);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public CompanyUserStatus statusEnum() {
        try {
            return status == null ? null : CompanyUserStatus.valueOf(status);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
