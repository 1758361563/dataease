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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyFeishuHttpDirectoryGateway implements CompanyFeishuDirectoryGateway {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final CompanyFeishuProperties properties;
    private final HttpClient http;

    public CompanyFeishuHttpDirectoryGateway(CompanyFeishuProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public Map<String, Object> search(String query) {
        JsonNode node = request("GET", "/v1/employees/search?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8),
                null, 60_000);
        List<Map<String, Object>> matches = new ArrayList<>();
        for (JsonNode item : node.path("matches")) {
            matches.add(publicEmployee(item));
        }
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("matches", matches);
        return vo;
    }

    @Override
    public Map<String, Object> resolve(String userId) {
        try {
            String payload = JSON.writeValueAsString(Map.of("userId", userId));
            return publicEmployee(request("POST", "/v1/employees/resolve", payload, 60_000));
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            LogUtil.error("feishu employee resolve encode error: " + e.getMessage());
            CompanyAdminErrors.invalid("无法查询通讯录员工");
            return Map.of();
        }
    }

    private JsonNode request(String method, String path, String payload, int timeoutMs) {
        if (StringUtils.isBlank(properties.getInternalToken())) {
            CompanyAdminErrors.invalid("独立服务令牌未配置");
        }
        String base = StringUtils.removeEnd(StringUtils.defaultIfBlank(properties.getShotBaseUrl(), "http://127.0.0.1:8091"), "/");
        if (!CompanyFeishuHttpDeliveryGateway.isAllowedShotBase(base)) {
            CompanyAdminErrors.invalid("独立服务只允许本机回环地址或 Docker host.docker.internal");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofMillis(Math.max(timeoutMs, properties.getConnectTimeoutMs())))
                    .header("Authorization", "Bearer " + properties.getInternalToken());
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload == null ? "{}" : payload));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = parse(response.body());
            if (response.statusCode() == 200) {
                return node;
            }
            String error = node.path("error").asText("独立服务通讯录查询失败");
            String missing = node.path("missingScope").asText(null);
            if (StringUtils.isNotBlank(missing)) {
                error = error + "；缺失权限：" + missing;
            }
            if (response.statusCode() == 404) {
                CompanyAdminErrors.invalid("通讯录中未找到该员工");
            }
            CompanyAdminErrors.invalid(error);
            return node;
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            LogUtil.error("feishu directory request error: " + e.getMessage());
            CompanyAdminErrors.invalid("无法连接独立执行服务");
            return JSON.missingNode();
        }
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(StringUtils.defaultIfBlank(body, "{}"));
        } catch (Exception e) {
            return JSON.createObjectNode().put("error", "独立服务返回无法解析");
        }
    }

    private static Map<String, Object> publicEmployee(JsonNode node) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("name", node.path("name").asText(null));
        vo.put("userId", node.path("userId").asText(null));
        vo.put("userIdTail", node.path("userIdTail").asText(null));
        List<String> departments = new ArrayList<>();
        for (JsonNode dept : node.path("departments")) {
            if (dept.isTextual() && StringUtils.isNotBlank(dept.asText())) {
                departments.add(dept.asText());
            }
        }
        vo.put("departments", departments);
        return vo;
    }
}
