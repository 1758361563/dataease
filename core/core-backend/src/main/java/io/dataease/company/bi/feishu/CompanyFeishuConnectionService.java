package io.dataease.company.bi.feishu;

import io.dataease.company.bi.admin.CompanyAdminErrors;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.integration.IntegrationConnectionStore;
import io.dataease.utils.IDUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

@Service
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyFeishuConnectionService {
    public static final String PROVIDER = "FEISHU";
    public static final String CONNECTION = "default";
    private final IntegrationConnectionStore connections;
    private final CompanyFeishuRepository legacy;
    private final LongSupplier ids;
    @Autowired
    public CompanyFeishuConnectionService(IntegrationConnectionStore connections, CompanyFeishuRepository legacy) {
        this(connections, legacy, IDUtils::snowID);
    }
    CompanyFeishuConnectionService(IntegrationConnectionStore connections, CompanyFeishuRepository legacy, LongSupplier ids) {
        this.connections = connections; this.legacy = legacy; this.ids = ids;
    }
    public Map<String, Object> get() {
        var connection = connections.find(PROVIDER, CONNECTION);
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("provider", PROVIDER); vo.put("connectionKey", CONNECTION);
        vo.put("configured", connection != null);
        vo.put("appId", connection == null ? legacyAppId() : connection.config().get("appId"));
        vo.put("runtimeEnabled", connection != null && Boolean.TRUE.equals(connection.config().get("runtimeEnabled")));
        vo.put("screenshotUser", connection == null ? "" : connection.config().getOrDefault("screenshotUser", ""));
        vo.put("hasAppSecret", connection != null && !connection.secrets().getOrDefault("appSecret", "").isBlank());
        vo.put("hasScreenshotPassword", connection != null && !connection.secrets().getOrDefault("screenshotPassword", "").isBlank());
        return vo;
    }
    public boolean enabled(String key) { return Boolean.TRUE.equals(get().get(key)); }
    public void requireExecutionReady() {
        Map<String, Object> settings = get();
        if (!Boolean.TRUE.equals(settings.get("runtimeEnabled"))) CompanyAdminErrors.invalid("请先启用飞书应用连接");
        if (String.valueOf(settings.getOrDefault("screenshotUser", "")).isBlank()
                || !Boolean.TRUE.equals(settings.get("hasScreenshotPassword")))
            CompanyAdminErrors.invalid("请先在应用连接中填写有仪表板查看权限的 BI 截图账号和密码");
    }
    private String legacyAppId() {
        var app = legacy.findAppConfig();
        return app == null ? "" : app.appId;
    }
    @Transactional
    public Map<String, Object> save(Map<String, Object> body, long operator) {
        for (String key : body.keySet()) {
            if (!Set.of("appId", "appSecret", "screenshotUser", "screenshotPassword",
                    "runtimeEnabled").contains(key))
                CompanyAdminErrors.invalid("不支持的连接配置项：" + key);
        }
        String appId = text(body, "appId", 64, true);
        var old = connections.find(PROVIDER, CONNECTION);
        var oldApp = legacy.findAppConfig();
        boolean changedApp = oldApp != null && !appId.equals(oldApp.appId);
        if (changedApp && legacy.listSubscriptions().stream().anyMatch(s -> "ENABLED".equals(s.status)))
            CompanyAdminErrors.invalid("更换飞书应用前请先停用订阅；原收件人属于原应用，不会自动迁移");
        Map<String, String> secrets = new LinkedHashMap<>(old == null ? Map.of() : old.secrets());
        for (String key : Set.of("appSecret", "screenshotPassword")) {
            String value = text(body, key, 4096, false);
            if (!value.isBlank()) secrets.put(key, value);
        }
        if (secrets.getOrDefault("appSecret", "").isBlank() ||
                (changedApp && text(body, "appSecret", 4096, false).isBlank()))
            CompanyAdminErrors.invalid("请填写当前飞书应用的 App Secret");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("appId", appId);
        config.put("screenshotUser", text(body, "screenshotUser", 128, false));
        for (String key : Set.of("runtimeEnabled")) {
            Object raw = body.get(key);
            if (raw != null && !(raw instanceof Boolean)) CompanyAdminErrors.invalid(key + " 必须是布尔值");
            config.put(key, Boolean.TRUE.equals(raw));
        }
        if (Boolean.TRUE.equals(config.get("runtimeEnabled")) && legacy.listSubscriptions().stream().anyMatch(s -> "ENABLED".equals(s.status)) &&
                (String.valueOf(config.get("screenshotUser")).isBlank() || secrets.getOrDefault("screenshotPassword", "").isBlank()))
            CompanyAdminErrors.invalid("已有启用的卡片订阅，请保留可用的 BI 截图账号和密码");
        connections.save(PROVIDER, CONNECTION, config, secrets, operator);
        long now = System.currentTimeMillis();
        var row = oldApp == null ? new CompanyFeishuRepository.AppConfig() : oldApp;
        if (oldApp == null) { row.id = ids.getAsLong(); row.createdAt = now; row.createdBy = operator; }
        row.appId = appId; row.appSecretRef = "BI_MANAGED_FEISHU_SECRET";
        row.status = Boolean.TRUE.equals(config.get("runtimeEnabled")) ? "ENABLED" : "DISABLED";
        row.lastVerifiedAt = null; row.lastVerifyError = null;
        row.updatedAt = now; row.updatedBy = operator;
        if (oldApp == null) legacy.insertAppConfig(row); else legacy.updateAppConfig(row);
        return get();
    }
    private static String text(Map<String, Object> body, String key, int max, boolean required) {
        Object raw = body.get(key);
        if (raw != null && !(raw instanceof String)) CompanyAdminErrors.invalid(key + " 必须是文字");
        String value = raw == null ? "" : (String) raw;
        if (!key.toLowerCase().contains("secret") && !key.toLowerCase().contains("password")) value = value.trim();
        if (value.length() > max || (required && value.isBlank())) CompanyAdminErrors.invalid(key + " 为空或过长");
        return value;
    }
}
