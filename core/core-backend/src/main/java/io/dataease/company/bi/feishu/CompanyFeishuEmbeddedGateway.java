package io.dataease.company.bi.feishu;

import io.dataease.company.bi.auth.CompanyAuthSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@Primary
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyFeishuEmbeddedGateway implements CompanyFeishuDirectoryGateway, CompanyFeishuDeliveryGateway {
    private final CompanyFeishuWorker worker;
    public CompanyFeishuEmbeddedGateway(CompanyFeishuWorker worker) { this.worker = worker; }
    @Override public Map<String, Object> search(String query) { return worker.call("search", Map.of("query", query)); }
    @Override public Map<String, Object> resolve(String userId) { return worker.call("resolve", Map.of("userId", userId)); }
    @Override public Map<String, Object> start(long subscriptionId, long operatorId, boolean dryRun) {
        return worker.call("start", Map.of("subscriptionId", String.valueOf(subscriptionId), "operatorUserId", String.valueOf(operatorId), "dryRun", dryRun));
    }
}
