package io.dataease.company.bi.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.company.bi.admin.CompanyAdminErrors;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.exception.DEException;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyFeishuHttpDeliveryGateway implements CompanyFeishuDeliveryGateway {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final CompanyFeishuProperties properties;
    private final HttpClient http;

    public CompanyFeishuHttpDeliveryGateway(CompanyFeishuProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public Map<String, Object> start(long subscriptionId, long operatorId, boolean dryRun) {
        if (StringUtils.isBlank(properties.getInternalToken())) {
            CompanyAdminErrors.invalid("独立服务令牌未配置");
        }
        String base = StringUtils.removeEnd(StringUtils.defaultIfBlank(properties.getShotBaseUrl(), "http://127.0.0.1:8091"), "/");
        if (!isAllowedShotBase(base)) {
            CompanyAdminErrors.invalid("独立服务只允许本机回环地址或 Docker host.docker.internal");
        }
        try {
            String payload = JSON.writeValueAsString(Map.of(
                    "subscriptionId", String.valueOf(subscriptionId),
                    "operatorUserId", operatorId,
                    "triggerType", "MANUAL",
                    "dryRun", dryRun
            ));
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/deliveries"))
                    .timeout(Duration.ofMillis(Math.max(1000, properties.getConnectTimeoutMs())))
                    .header("Authorization", "Bearer " + properties.getInternalToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 202) {
                LogUtil.error("feishu delivery start failed status=" + response.statusCode());
                CompanyAdminErrors.invalid("独立服务未能启动执行");
            }
            JsonNode node = JSON.readTree(response.body());
            String runId = node.path("runId").asText(null);
            if (StringUtils.isBlank(runId)) {
                CompanyAdminErrors.invalid("独立服务未返回 runId");
            }
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("runId", runId);
            vo.put("status", node.path("status").asText("PENDING"));
            vo.put("accepted", true);
            return vo;
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            LogUtil.error("feishu delivery start error: " + e.getMessage());
            CompanyAdminErrors.invalid("无法连接独立执行服务");
            return Map.of();
        }
    }

    /**
     * DataEase 跑在容器里时，127.0.0.1 是容器自己，不是宿主机上的独立服务。
     * Docker Desktop 可通过 host.docker.internal 访问宿主机 127.0.0.1 监听端口。
     */
    static boolean isAllowedShotBase(String base) {
        if (StringUtils.isBlank(base)) {
            return false;
        }
        try {
            URI uri = URI.create(base);
            if (!"http".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            return "127.0.0.1".equals(host)
                    || "localhost".equalsIgnoreCase(host)
                    || "host.docker.internal".equalsIgnoreCase(host);
        } catch (Exception e) {
            return false;
        }
    }
}
