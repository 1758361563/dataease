package io.dataease.company.bi.feishu;

import io.dataease.auth.vo.TokenVO;
import io.dataease.company.bi.admin.CompanyAdminErrors;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.auth.CompanyTokenIssuer;
import io.dataease.company.bi.user.CompanyBiUserService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

@Service
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class FeishuLoginService {
    private final FeishuLoginSettings settings;
    private final FeishuOAuthClient oauth;
    private final CompanyFeishuIdentityService identities;
    private final CompanyBiUserService users;
    private final CompanyTokenIssuer tokens;
    private final JdbcTemplate jdbc;
    private static final SecureRandom RANDOM = new SecureRandom();
    public FeishuLoginService(FeishuLoginSettings settings, FeishuOAuthClient oauth,
                             CompanyFeishuIdentityService identities, CompanyBiUserService users,
                             CompanyTokenIssuer tokens, JdbcTemplate jdbc) {
        this.settings=settings; this.oauth=oauth; this.identities=identities;
        this.users=users; this.tokens=tokens; this.jdbc=jdbc;
    }
    public Map<String, Object> start(String verifier, String mode) {
        requireVerifier(verifier);
        if (!"QR".equals(mode) && !"CLIENT".equals(mode)) CompanyAdminErrors.invalid("登录方式无效");
        var config = settings.requireEnabled();
        String appId = String.valueOf(config.get("appId"));
        String state = random(), callback = settings.callback(config);
        long now = System.currentTimeMillis();
        jdbc.update("DELETE FROM company_feishu_login_attempt WHERE expires_at < ?", now);
        jdbc.update("INSERT INTO company_feishu_login_attempt(state_hash,verifier_hash,app_id,tenant_key,callback_url,login_mode,expires_at) VALUES (?,?,?,?,?,?,?)",
                hash(state), hash(verifier), appId, config.get("tenantKey"), callback, mode, now + 300_000);
        String url = "https://passport.feishu.cn/suite/passport/oauth/authorize?client_id=" + enc(appId)
                + "&redirect_uri=" + enc(callback) + "&response_type=code&state=" + enc(state);
        return Map.of("state",state,"appId",appId,"authorizeUrl",url,"expiresAt",now+300_000);
    }
    public TokenVO complete(String state, String verifier, String code) {
        requireVerifier(verifier);
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}") || code == null || code.isBlank() || code.length()>2048)
            CompanyAdminErrors.invalid("登录请求无效，请重新登录");
        var config = settings.requireEnabled();
        var attempts = jdbc.queryForList("SELECT * FROM company_feishu_login_attempt WHERE state_hash=? AND verifier_hash=? AND consumed=0 AND expires_at>?",
                hash(state), hash(verifier), System.currentTimeMillis());
        if (attempts.isEmpty()) CompanyAdminErrors.invalid("登录请求已过期或已使用，请重新登录");
        var attempt = attempts.get(0);
        if (!config.get("appId").equals(attempt.get("app_id")) || !config.get("tenantKey").equals(attempt.get("tenant_key"))
                || !settings.callback(config).equals(attempt.get("callback_url"))) CompanyAdminErrors.invalid("登录配置已更新，请重新登录");
        // Atomic consumption committed before the remote request: failed exchanges cannot replay a code.
        if (jdbc.update("UPDATE company_feishu_login_attempt SET consumed=1 WHERE state_hash=? AND verifier_hash=? AND consumed=0 AND expires_at>?",
                hash(state), hash(verifier), System.currentTimeMillis()) != 1) CompanyAdminErrors.invalid("登录请求已使用，请重新登录");
        String appId = String.valueOf(config.get("appId"));
        var identity = oauth.exchange(appId, settings.app().secrets().get("appSecret"), code,
                settings.callback(config), String.valueOf(attempt.get("login_mode")));
        if (!config.get("tenantKey").equals(identity.tenantKey())) CompanyAdminErrors.invalid("请使用本企业的飞书账号登录");
        var binding = identities.loginBinding(appId, identity.userId());
        var user = users.findById(binding.biUserId);
        if (user == null || !user.isEnabled()) CompanyAdminErrors.invalid("BI 用户已停用，请联系管理员");
        users.updateLastLogin(user.getId(), System.currentTimeMillis());
        return tokens.issueFeishu(user.getId(), binding.id, appId);
    }
    static void requireVerifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43,128}")) CompanyAdminErrors.invalid("登录校验参数无效");
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static String random() { byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
