package io.dataease.company.bi.auth;

import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.exception.DEException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CompanyUserStatusGuard {

    private final CompanyBiUserService userService;

    public CompanyUserStatusGuard(CompanyBiUserService userService) {
        this.userService = userService;
    }

    public CompanyBiUser requireActive(Long userId) {
        CompanyBiUser user = userService.findById(userId);
        if (user == null || !user.isEnabled()) {
            DEException.throwException(HttpStatus.UNAUTHORIZED.value(), "账号不可用");
        }
        return user;
    }
}
