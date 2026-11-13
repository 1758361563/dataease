package io.dataease.company.bi.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.admin.CompanyAdminErrors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class FeishuOAuthClient {
    private final HttpClient http;
    @org.springframework.beans.factory.annotation.Autowired
    public FeishuOAuthClient() { this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()); }
    FeishuOAuthClient(HttpClient http) { this.http = http; }
    private final ObjectMapper json = new ObjectMapper();
    public record Identity(String userId, String tenantKey) {}
    public Identity exchange(String appId, String secret, String code, String redirect, String mode) {
        var body = new java.util.LinkedHashMap<String, String>();
        body.put("grant_type", "authorization_code"); body.put("client_id", appId);
        body.put("client_secret", secret); body.put("code", code);
        if ("QR".equals(mode)) body.put("redirect_uri", redirect);
        boolean qr = "QR".equals(mode);
        var token = call(qr ? "https://passport.feishu.cn/suite/passport/oauth/token"
                : "https://open.feishu.cn/open-apis/authen/v2/oauth/token", body, null, qr);
        String access = token.path("access_token").asText();
        if (access.isBlank()) CompanyAdminErrors.invalid("飞书授权已失效，请重新登录");
        var response = call(qr ? "https://passport.feishu.cn/suite/passport/oauth/userinfo"
                : "https://open.feishu.cn/open-apis/authen/v1/user_info", null, access, false);
        var user = qr ? response : response.path("data");
        String uid = user.path("user_id").asText(), tenant = user.path("tenant_key").asText();
        if (uid.isBlank() || tenant.isBlank()) CompanyAdminErrors.invalid("飞书未返回企业或用户 ID，请检查获取用户 User ID 权限");
        return new Identity(uid, tenant);
    }
    private JsonNode call(String url, Map<String, String> body, String token, boolean form) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
            if (token != null) request.header("Authorization", "Bearer " + token);
            if (body != null) {
                String payload = form ? body.entrySet().stream()
                        .map(entry -> FeishuLoginService.enc(entry.getKey()) + "=" + FeishuLoginService.enc(entry.getValue()))
                        .collect(java.util.stream.Collectors.joining("&")) : json.writeValueAsString(body);
                request.header("Content-Type", form ? "application/x-www-form-urlencoded" : "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload));
            }
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException();
            var data = json.readTree(response.body());
            if (data.has("error") || (data.has("code") && data.path("code").asInt() != 0)) throw new IllegalStateException();
            return data;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("飞书认证请求已中断");
        } catch (Exception e) {
            // Never log tokens, authorization codes, secrets or raw provider responses.
            CompanyAdminErrors.invalid("飞书认证失败，请重新授权；持续失败时检查应用凭据、权限和回调地址");
            return null;
        }
    }
}
