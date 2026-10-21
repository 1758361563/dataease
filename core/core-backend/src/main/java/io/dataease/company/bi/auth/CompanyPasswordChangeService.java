package io.dataease.company.bi.auth;

import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.exception.DEException;
import org.springframework.stereotype.Component;

@Component
public class CompanyPasswordChangeService {
    private final CompanyBiUserService userService;
    private final CompanyPasswordHasher hasher;

    public CompanyPasswordChangeService(CompanyBiUserService userService, CompanyPasswordHasher hasher) {
        this.userService = userService;
        this.hasher = hasher;
    }

    public void change(CompanyBiUser user, String oldPassword, String newPassword) {
        if (user == null || !user.isEnabled()) {
            DEException.throwException("用户不可用");
        }
        if (!hasher.matches(oldPassword, user.getPasswordHash())) {
            DEException.throwException("原密码不正确");
        }
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 128
                || !newPassword.matches("[\\x21-\\x7e]+")
                || !newPassword.matches(".*[a-z].*") || !newPassword.matches(".*[A-Z].*")
                || !newPassword.matches(".*[0-9].*") || !newPassword.matches(".*[^a-zA-Z0-9].*")) {
            DEException.throwException("新密码须为8至128位，包含大小写字母、数字和特殊字符");
        }
        if (hasher.matches(newPassword, user.getPasswordHash())) {
            DEException.throwException("新密码不能与原密码相同");
        }
        if (!userService.updatePassword(user.getId(), user.getPasswordHash(), hasher.hash(newPassword))) {
            DEException.throwException("密码已发生变更，请重新登录后重试");
        }
    }
}
