package io.dataease.company.bi.feishu;

import java.util.List;

public interface CompanyFeishuRepository {

    class AppConfig {
        public Long id;
        public String appId;
        public String appSecretRef;
        public String status;
        public Long lastVerifiedAt;
        public String lastVerifyError;
        public Long createdAt;
        public Long createdBy;
        public Long updatedAt;
        public Long updatedBy;
    }

    class Recipient {
        public Long id;
        public String recipientType;
        public String receiveIdType;
        public String displayName;
        public String feishuId;
        public String unionId;
        public String appId;
        public String source;
        public String identityStatus;
        public String verifyStatus;
        public String status;
        public Long lastVerifiedAt;
        public Long createdAt;
        public Long createdBy;
        public Long updatedAt;
        public Long updatedBy;
    }

    class IdentityMap {
        public Long id;
        public Long biUserId;
        public Long recipientId;
        public String appId;
        public String source;
        public String status;
        public Long createdAt;
        public Long createdBy;
        public Long updatedAt;
        public Long updatedBy;
    }

    class Subscription {
        public Long id;
        public String name;
        public String dashboardId;
        public String chartId;
        public String chartName;
        public String titleTemplate;
        public String bodyTemplate;
        public Integer includeDetailLink;
        public String fixedFilterJson;
        public String bizDateRule;
        public String scheduleJson;
        public String freshnessJson;
        public String status;
        public String deliveryLane;
        public Long createdAt;
        public Long createdBy;
        public Long updatedAt;
        public Long updatedBy;
    }

    class DeliveryRun {
        public Long id;
        public Long subscriptionId;
        public String triggerType;
        public Long plannedAt;
        public Long operatorUserId;
        public String status;
        public String executionMode;
        public String screenshotStatus;
        public String uploadStatus;
        public String imageKey;
        public String errorSummary;
        public Long startedAt;
        public Long finishedAt;
        public Long createdAt;
    }

    class DeliveryTarget {
        public Long id;
        public Long runId;
        public Long recipientId;
        public String receiveIdType;
        public String feishuId;
        public String feishuMessageId;
        public String status;
        public Integer retryCount;
        public String errorSummary;
        public Long sentAt;
        public String displayName;
        public String recipientType;
    }

    AppConfig findAppConfig();

    AppConfig findAppConfigByAppId(String appId);

    void insertAppConfig(AppConfig row);

    void updateAppConfig(AppConfig row);

    List<Recipient> listRecipients();

    Recipient findRecipient(Long id);

    Recipient findRecipientByKey(String appId, String receiveIdType, String feishuId);

    void insertRecipient(Recipient row);

    void updateRecipient(Recipient row);

    IdentityMap findMapByRecipient(Long recipientId);

    IdentityMap findMapByUser(String appId, Long biUserId);

    void insertIdentityMap(IdentityMap row);

    List<Subscription> listSubscriptions();

    Subscription findSubscription(Long id);

    void insertSubscription(Subscription row);

    void updateSubscription(Subscription row);

    List<Long> listRecipientIds(Long subscriptionId);

    void deleteSubscriptionRecipients(Long subscriptionId);

    void insertSubscriptionRecipient(Long id, Long subscriptionId, Long recipientId, long createdAt, Long createdBy);

    List<DeliveryRun> listRuns(Long subscriptionId, int limit);

    DeliveryRun findRun(Long id);

    List<DeliveryTarget> listTargets(Long runId);
}
