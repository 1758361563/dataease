package io.dataease.company.bi.etl;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static io.dataease.company.bi.etl.EtlModel.*;

@Component
@ConditionalOnProperty(name="company.bi.auth.enabled", havingValue="true", matchIfMissing=true)
public class EtlSwitchService {
    private final EtlStore store;
    private final Environment environment;
    private volatile String instanceId;
    private volatile boolean resolved;
    public EtlSwitchService(EtlStore store, Environment environment) { this.store = store; this.environment = environment; }
    public String instanceId() {
        if (!resolved) resolve();
        return instanceId;
    }
    private synchronized void resolve() {
        if (resolved) return;
        // IMDSv2 prevents dependence on the host's optional legacy metadata configuration.
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).proxy(new java.net.ProxySelector() {
            public List<java.net.Proxy> select(URI uri) { return List.of(java.net.Proxy.NO_PROXY); }
            public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException e) { }
        }).build()) {
            var token = client.send(HttpRequest.newBuilder(URI.create("http://100.100.100.200/latest/api/token"))
                    .timeout(Duration.ofSeconds(1)).header("X-aliyun-ecs-metadata-token-ttl-seconds","60").PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            if (token.statusCode() == 200) {
                var id = client.send(HttpRequest.newBuilder(URI.create("http://100.100.100.200/latest/meta-data/instance-id"))
                        .timeout(Duration.ofSeconds(1)).header("X-aliyun-ecs-metadata-token", token.body()).GET().build(),HttpResponse.BodyHandlers.ofString());
                if (id.statusCode() == 200 && id.body().strip().matches("i-[A-Za-z0-9-]{2,100}")) instanceId = id.body().strip();
            }
        } catch (Exception ignored) { }
        if (instanceId == null) {
            String injected = environment.getProperty("COMPANY_BI_INSTANCE_ID", "").strip();
            if (injected.matches("[A-Za-z0-9_-]{3,128}")) instanceId = injected;
        }
        resolved = true;
    }
    public boolean hardDisabled() { return Boolean.parseBoolean(environment.getProperty("COMPANY_BI_ETL_HARD_DISABLE", "false")); }
    public String reason() {
        EtlStore.Setting setting = store.setting();
        if (hardDisabled()) return "运维已强制关闭 ETL";
        if (!setting.schedulerEnabled()) return "全局开关关闭";
        if (instanceId() == null) return "未取得实例 ID";
        if (!instanceId().equals(setting.enabledInstanceId())) return "开关属于另一实例";
        return "";
    }
    public boolean effective() { return reason().isEmpty(); }
    public void allow(String kind, Instant instant) {
        require(!hardDisabled(), "运维已强制关闭 ETL");
        if ("refresh".equals(kind)) require(effective(), "正式刷新需要本实例的全局开关生效");
        else {
            require("refresh_test".equals(kind), "运行类型无效");
            require(testTimeAllowed(instant), "测试执行时间请避开 02:30–05:00（上海时间）");
        }
    }
    public static boolean testTimeAllowed(Instant instant) {
        LocalTime time = instant.atZone(ZONE).toLocalTime();
        return time.isBefore(LocalTime.of(2,30)) || !time.isBefore(LocalTime.of(5,0));
    }
}
