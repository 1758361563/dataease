package io.dataease.company.bi.auth;

import io.dataease.auth.vo.TokenVO;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyLoginService {

    private final CompanyBiUserService userService;
    private final CompanyPasswordHasher passwordHasher;
    private final CompanyTokenIssuer tokenIssuer;

    public CompanyLoginService(CompanyBiUserService userService,
                               CompanyPasswordHasher passwordHasher,
                               CompanyTokenIssuer tokenIssuer) {
        this.userService = userService;
        this.passwordHasher = passwordHasher;
        this.tokenIssuer = tokenIssuer;
    }

    public TokenVO login(String username, String rawPassword) {
        if (StringUtils.isBlank(username) || rawPassword == null) {
            rejectBadCredentials();
        }
        CompanyBiUser user = userService.findByUsername(username.trim());
        if (user == null || !passwordHasher.matches(rawPassword, user.getPasswordHash())) {
            rejectBadCredentials();
        }
        if (!user.isEnabled()) {
            DEException.throwException(ResultCode.USER_ACCOUNT_FORBIDDEN.code(), ResultCode.USER_ACCOUNT_FORBIDDEN.message());
        }
        TokenVO token = tokenIssuer.issue(user.getId());
        userService.updateLastLogin(user.getId(), System.currentTimeMillis());
        return token;
    }

    private static void rejectBadCredentials() {
        DEException.throwException(ResultCode.USER_LOGIN_ERROR.code(), ResultCode.USER_LOGIN_ERROR.message());
    }
}
