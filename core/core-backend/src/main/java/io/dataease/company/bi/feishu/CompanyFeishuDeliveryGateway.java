package io.dataease.company.bi.feishu;

import java.util.Map;

public interface CompanyFeishuDeliveryGateway {

    Map<String, Object> start(long subscriptionId, long operatorId, boolean dryRun);
}
