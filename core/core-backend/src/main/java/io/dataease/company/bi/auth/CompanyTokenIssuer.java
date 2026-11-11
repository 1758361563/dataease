package io.dataease.company.bi.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import io.dataease.auth.bo.TokenUserBO;
import io.dataease.auth.vo.TokenVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyTokenIssuer {

    public static final long DEFAULT_OID = 1L;

    private final CompanyTokenSecret tokenSecret;

    public CompanyTokenIssuer(CompanyTokenSecret tokenSecret) {
        this.tokenSecret = tokenSecret;
    }

    public TokenVO issue(Long userId) {
        Algorithm algorithm = Algorithm.HMAC256(tokenSecret.getSecret());
        String token = JWT.create()
                .withIssuer("de-company-bi")
                .withClaim("uid", userId)
                .withClaim("oid", DEFAULT_OID)
                .sign(algorithm);
        return new TokenVO(token, 0L);
    }

    public TokenUserBO toUser(Long userId) {
        return new TokenUserBO(userId, DEFAULT_OID);
    }

    public TokenVO issueFeishu(Long userId, Long bindingId, String appId) {
        long expiry = System.currentTimeMillis() + 12 * 60 * 60 * 1000L;
        String token = JWT.create().withIssuer("de-company-bi")
                .withClaim("uid", userId).withClaim("oid", DEFAULT_OID)
                .withClaim("fs_binding", bindingId).withClaim("fs_app", appId)
                .withExpiresAt(new java.util.Date(expiry)).sign(Algorithm.HMAC256(tokenSecret.getSecret()));
        // The community frontend refresh endpoint does not support external login tokens.
        // JWT expiry is still enforced by the backend on every request.
        return new TokenVO(token, 0L);
    }
}
