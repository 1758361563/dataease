package io.dataease.company.bi.auth;

import io.dataease.company.bi.user.CompanyAccountRole;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.company.bi.user.CompanyUserStatus;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;

public class CompanyBiBootstrapper {

    public static final long BOOTSTRAP_ADMIN_ID = CompanyBiUserService.BOOTSTRAP_ADMIN_ID;

    private final CompanyBiUserService userService;
    private final CompanyPasswordHasher passwordHasher;

    public CompanyBiBootstrapper(CompanyBiUserService userService, CompanyPasswordHasher passwordHasher) {
        this.userService = userService;
        this.passwordHasher = passwordHasher;
    }

    /**
     * id=1 already present: leave password and display_name untouched.
     * id=1 missing: require a bootstrap password, then insert ADMIN / ENABLED.
     */
    public void ensureBootstrapAdmin(String username, String displayName, String rawPassword) {
        CompanyBiUser existing = userService.findById(BOOTSTRAP_ADMIN_ID);
        if (existing != null) {
            LogUtil.info("company_bi_user id=1 already exists; bootstrap password/display_name will not be overwritten");
            return;
        }
        if (StringUtils.isBlank(rawPassword)) {
            throw new IllegalStateException(
                    "COMPANY_BI_BOOTSTRAP_PASSWORD is required to create bootstrap admin id=1. Do not write the password into Git.");
        }
        if (StringUtils.isBlank(username)) {
            throw new IllegalStateException("company.bi.bootstrap-username must not be blank");
        }
        CompanyBiUser taken = userService.findByUsername(username);
        if (taken != null) {
            throw new IllegalStateException("bootstrap username '" + username + "' is already used by user id=" + taken.getId());
        }
        long now = System.currentTimeMillis();
        CompanyBiUser admin = new CompanyBiUser();
        admin.setId(BOOTSTRAP_ADMIN_ID);
        admin.setUsername(username.trim());
        admin.setDisplayName(StringUtils.isBlank(displayName) ? username.trim() : displayName.trim());
        admin.setPasswordHash(passwordHasher.hash(rawPassword));
        admin.setAccountRole(CompanyAccountRole.ADMIN.name());
        admin.setStatus(CompanyUserStatus.ENABLED.name());
        admin.setCreatedAt(now);
        admin.setCreatedBy(BOOTSTRAP_ADMIN_ID);
        admin.setUpdatedAt(now);
        admin.setUpdatedBy(BOOTSTRAP_ADMIN_ID);
        userService.insert(admin);
        LogUtil.info("Created bootstrap system administrator company_bi_user.id=1");
    }
}
