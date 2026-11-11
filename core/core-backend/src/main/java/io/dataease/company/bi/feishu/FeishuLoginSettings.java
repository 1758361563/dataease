package io.dataease.company.bi.feishu;

import io.dataease.company.bi.admin.CompanyAdminErrors;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.integration.IntegrationConnectionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.Map;

@Service
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class FeishuLoginSettings {
    private final IntegrationConnectionStore store;
    public FeishuLoginSettings(IntegrationConnectionStore store) { this.store = store; }
    public Map<String, Object> get() {
        var row = store.find("FEISHU", "login");
        return row == null ? Map.of("enabled", false, "baseUrl", "", "tenantKey", "", "appId", "") : row.config();
    }
    public IntegrationConnectionStore.Connection app() {
        var app = store.find("FEISHU", "default");
        if (app == null || app.secrets().getOrDefault("appSecret", "").isBlank())
            CompanyAdminErrors.invalid("请先保存飞书应用连接与密钥");
        return app;
    }
    public Map<String, Object> requireEnabled() {
        var settings = get();
        if (!Boolean.TRUE.equals(settings.get("enabled")) || !settings.get("appId").equals(app().config().get("appId")))
            CompanyAdminErrors.invalid("飞书登录尚未启用，或应用已更换，请联系管理员重新配置");
        return settings;
    }
    public Map<String, Object> save(Map<String, Object> body, long operator) {
        String base = String.valueOf(body.getOrDefault("baseUrl", "")).trim().replaceAll("/+$", "");
        String tenant = String.valueOf(body.getOrDefault("tenantKey", "")).trim();
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        if (!base.isEmpty() || enabled) validateBase(base);
        if (enabled && (tenant.isBlank() || tenant.length() > 128)) CompanyAdminErrors.invalid("请填写飞书企业 tenant_key");
        String appId = String.valueOf(app().config().get("appId"));
        var settings = Map.<String, Object>of("enabled", enabled, "baseUrl", base, "tenantKey", tenant, "appId", appId);
        store.save("FEISHU", "login", settings, Map.of(), operator);
        return settings;
    }
    static void validateBase(String base) {
        try {
            URI uri = URI.create(base);
            boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
            if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null
                    || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme()))) || base.length() > 1024)
                throw new IllegalArgumentException();
        } catch (RuntimeException ex) { CompanyAdminErrors.invalid("BI 访问地址须为 HTTPS 地址，本地调试可用 localhost HTTP"); }
    }
    public String callback(Map<String, Object> settings) { return settings.get("baseUrl") + "/de2api/login/feishu/callback"; }
}
