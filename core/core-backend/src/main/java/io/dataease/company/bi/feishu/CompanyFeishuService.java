package io.dataease.company.bi.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.company.bi.admin.CompanyAdminErrors;
import io.dataease.company.bi.admin.CompanyAdminTx;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.permission.CompanyDashboardGuard;
import io.dataease.exception.DEException;
import io.dataease.utils.IDUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyFeishuService {

    private static final Set<String> RECEIVE_ID_TYPES = Set.of("user_id", "chat_id");
    private static final Set<String> RECIPIENT_TYPES = Set.of("PERSONAL", "CHAT");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Set<String> SUB_STATUSES = Set.of("DRAFT", "ENABLED", "DISABLED", "DELETED");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CompanyFeishuRepository store;
    private final CompanyDashboardGuard dashboardGuard;
    private final CompanyAdminTx tx;
    private final CompanyFeishuProperties properties;
    private final CompanyFeishuDeliveryGateway deliveryGateway;
    private final CompanyFeishuDirectoryGateway directory;
    private final LongSupplier ids;

    @Autowired
    public CompanyFeishuService(CompanyFeishuRepository store,
                                CompanyDashboardGuard dashboardGuard,
                                CompanyAdminTx tx,
                                CompanyFeishuProperties properties,
                                CompanyFeishuDeliveryGateway deliveryGateway,
                                CompanyFeishuDirectoryGateway directory) {
        this(store, dashboardGuard, tx, properties, deliveryGateway, IDUtils::snowID, directory);
    }

    CompanyFeishuService(CompanyFeishuRepository store,
                         CompanyDashboardGuard dashboardGuard,
                         CompanyAdminTx tx,
                         CompanyFeishuProperties properties,
                         CompanyFeishuDeliveryGateway deliveryGateway,
                         LongSupplier ids) {
        this(store, dashboardGuard, tx, properties, deliveryGateway, ids, null);
    }

    CompanyFeishuService(CompanyFeishuRepository store,
                         CompanyDashboardGuard dashboardGuard,
                         CompanyAdminTx tx,
                         CompanyFeishuProperties properties,
                         CompanyFeishuDeliveryGateway deliveryGateway,
                         LongSupplier ids,
                         CompanyFeishuDirectoryGateway directory) {
        this.store = store;
        this.dashboardGuard = dashboardGuard;
        this.tx = tx;
        this.properties = properties;
        this.deliveryGateway = deliveryGateway;
        this.ids = ids;
        this.directory = directory;
    }

    public Map<String, Object> getIntegration() {
        CompanyFeishuRepository.AppConfig row = store.findAppConfig();
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("configured", row != null);
        if (row == null) {
            vo.put("appId", null);
            vo.put("appSecretRef", null);
            vo.put("status", null);
            vo.put("lastVerifiedAt", null);
            vo.put("hasSecretRef", false);
            return vo;
        }
        vo.put("id", String.valueOf(row.id));
        vo.put("appId", row.appId);
        vo.put("appSecretRef", row.appSecretRef);
        vo.put("status", row.status);
        vo.put("lastVerifiedAt", row.lastVerifiedAt);
        vo.put("hasSecretRef", StringUtils.isNotBlank(row.appSecretRef));
        return vo;
    }

    public Map<String, Object> updateIntegration(Map<String, Object> body, long operatorId) {
        rejectSecretFields(body);
        String appId = requiredText(body, "appId", 64);
        String appSecretRef = requiredSecretRef(body.get("appSecretRef"));
        String status = optionalEnum(body.get("status"), STATUSES, "ENABLED");
        long now = System.currentTimeMillis();
        CompanyFeishuRepository.AppConfig existing = store.findAppConfig();
        if (existing == null) {
            CompanyFeishuRepository.AppConfig row = new CompanyFeishuRepository.AppConfig();
            row.id = ids.getAsLong();
            row.appId = appId;
            row.appSecretRef = appSecretRef;
            row.status = status;
            row.createdAt = now;
            row.createdBy = operatorId;
            row.updatedAt = now;
            row.updatedBy = operatorId;
            store.insertAppConfig(row);
        } else {
            existing.appId = appId;
            existing.appSecretRef = appSecretRef;
            existing.status = status;
            existing.updatedAt = now;
            existing.updatedBy = operatorId;
            store.updateAppConfig(existing);
        }
        return getIntegration();
    }

    public Map<String, Object> testIntegration() {
        CompanyFeishuRepository.AppConfig row = store.findAppConfig();
        if (row == null || StringUtils.isBlank(row.appId) || StringUtils.isBlank(row.appSecretRef)) {
            CompanyAdminErrors.invalid("飞书应用配置不完整");
        }
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("ok", true);
        vo.put("checked", "app_id and app_secret_ref present");
        vo.put("feishuCalled", false);
        return vo;
    }

    public List<Map<String, Object>> listRecipients() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CompanyFeishuRepository.Recipient row : store.listRecipients()) {
            result.add(recipientVo(row));
        }
        return result;
    }

    public Map<String, Object> createRecipient(Map<String, Object> body, long operatorId) {
        rejectSecretFields(body);
        CompanyFeishuRepository.Recipient row = newRecipient(body, operatorId, true);
        if (store.findRecipientByKey(row.appId, row.receiveIdType, row.feishuId) != null) {
            CompanyAdminErrors.conflict("收件人已存在");
        }
        return tx.execute(() -> {
            store.insertRecipient(row);
            maybeMapIdentity(body, row, operatorId);
            return recipientVo(store.findRecipient(row.id));
        });
    }

    public Map<String, Object> updateRecipient(Long id, Map<String, Object> body, long operatorId) {
        rejectSecretFields(body);
        CompanyFeishuRepository.Recipient existing = requireRecipient(id);
        CompanyFeishuRepository.Recipient row = newRecipient(body, operatorId, false);
        row.id = existing.id;
        row.createdAt = existing.createdAt;
        row.createdBy = existing.createdBy;
        row.verifyStatus = existing.verifyStatus;
        row.lastVerifiedAt = existing.lastVerifiedAt;
        if (body.get("status") == null) {
            row.status = existing.status;
        }
        CompanyFeishuRepository.Recipient clash = store.findRecipientByKey(row.appId, row.receiveIdType, row.feishuId);
        if (clash != null && !clash.id.equals(existing.id)) {
            CompanyAdminErrors.conflict("收件人已存在");
        }
        if (!StringUtils.equals(existing.feishuId, row.feishuId) || !StringUtils.equals(existing.receiveIdType, row.receiveIdType)) {
            row.identityStatus = "UNCONFIRMED";
        } else {
            row.identityStatus = existing.identityStatus;
        }
        store.updateRecipient(row);
        return recipientVo(store.findRecipient(id));
    }

    public Map<String, Object> setRecipientStatus(Long id, String status, long operatorId) {
        if (!STATUSES.contains(status)) {
            CompanyAdminErrors.invalid("status 非法");
        }
        CompanyFeishuRepository.Recipient row = requireRecipient(id);
        row.status = status;
        row.updatedAt = System.currentTimeMillis();
        row.updatedBy = operatorId;
        store.updateRecipient(row);
        return recipientVo(store.findRecipient(id));
    }

    public List<Map<String, Object>> listSubscriptions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CompanyFeishuRepository.Subscription row : store.listSubscriptions()) {
            result.add(subscriptionVo(row, false));
        }
        return result;
    }

    public Map<String, Object> getSubscription(Long id) {
        return subscriptionVo(requireSubscription(id), true);
    }

    public Map<String, Object> createSubscription(Map<String, Object> body, long operatorId) {
        rejectSecretFields(body);
        CompanyFeishuRepository.Subscription row = newSubscription(body, operatorId, true);
        requireDashboardView(row.dashboardId);
        List<Long> recipientIds = parseRecipientIds(body.get("recipientIds"));
        validateRecipients(recipientIds);
        return tx.execute(() -> {
            store.insertSubscription(row);
            replaceRecipients(row.id, recipientIds, operatorId);
            return subscriptionVo(store.findSubscription(row.id), true);
        });
    }

    public Map<String, Object> updateSubscription(Long id, Map<String, Object> body, long operatorId) {
        rejectSecretFields(body);
        CompanyFeishuRepository.Subscription existing = requireSubscription(id);
        CompanyFeishuRepository.Subscription row = newSubscription(body, operatorId, false);
        row.id = existing.id;
        row.createdAt = existing.createdAt;
        row.createdBy = existing.createdBy;
        if (body.get("status") == null) {
            row.status = existing.status;
        }
        if (!body.containsKey("freshnessJson")) {
            row.freshnessJson = existing.freshnessJson;
        }
        requireDashboardView(row.dashboardId);
        List<Long> recipientIds = body.containsKey("recipientIds")
                ? parseRecipientIds(body.get("recipientIds"))
                : store.listRecipientIds(id);
        validateRecipients(recipientIds);
        return tx.execute(() -> {
            store.updateSubscription(row);
            replaceRecipients(id, recipientIds, operatorId);
            return subscriptionVo(store.findSubscription(id), true);
        });
    }

    public Map<String, Object> setSubscriptionStatus(Long id, String status, long operatorId) {
        if (!SUB_STATUSES.contains(status)) {
            CompanyAdminErrors.invalid("status 非法");
        }
        CompanyFeishuRepository.Subscription row = requireSubscription(id);
        requireDashboardView(row.dashboardId);
        row.status = status;
        row.updatedAt = System.currentTimeMillis();
        row.updatedBy = operatorId;
        store.updateSubscription(row);
        return subscriptionVo(store.findSubscription(id), true);
    }

    public Map<String, Object> runSubscription(Long id, long operatorId) {
        return runSubscription(id, operatorId, null);
    }

    public Map<String, Object> runSubscription(Long id, long operatorId, Object requestDryRun) {
        CompanyFeishuRepository.Subscription row = requireSubscription(id);
        if (!"ENABLED".equals(row.status)) {
            CompanyAdminErrors.invalid("只有 ENABLED 订阅可以手动执行");
        }
        requireDashboardView(row.dashboardId);
        if (store.listRecipientIds(id).isEmpty()) {
            CompanyAdminErrors.invalid("订阅没有收件人");
        }
        if (store.findAppConfig() == null) {
            CompanyAdminErrors.invalid("飞书应用未配置");
        }
        boolean dryRun = properties.isDryRun() || truthy(requestDryRun);
        return deliveryGateway.start(id, operatorId, dryRun);
    }

    public List<Map<String, Object>> listRuns(Long subscriptionId) {
        requireSubscription(subscriptionId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CompanyFeishuRepository.DeliveryRun run : store.listRuns(subscriptionId, 50)) {
            result.add(runVo(run, false));
        }
        return result;
    }

    public Map<String, Object> getRun(Long subscriptionId, Long runId) {
        requireSubscription(subscriptionId);
        CompanyFeishuRepository.DeliveryRun run = store.findRun(runId);
        if (run == null || !subscriptionId.equals(run.subscriptionId)) {
            CompanyAdminErrors.invalid("执行记录不存在");
        }
        return runVo(run, true);
    }

    public Map<String, Object> searchEmployees(String query) {
        if (StringUtils.isBlank(query)) {
            CompanyAdminErrors.invalid("query 不能为空");
        }
        requireDirectory();
        return directory.search(query.trim());
    }

    public Map<String, Object> confirmEmployee(Map<String, Object> body, long operatorId) {
        rejectSecretFields(body);
        requireDirectory();
        CompanyFeishuRepository.AppConfig app = store.findAppConfig();
        if (app == null) {
            CompanyAdminErrors.invalid("请先由系统管理员配置飞书应用");
        }
        String userId = requiredText(body, "userId", 64);
        if (userId.startsWith("ou_") || userId.startsWith("oc_")) {
            CompanyAdminErrors.invalid("userId 不是可用于机器人私聊的 User ID");
        }
        Map<String, Object> employee = directory.resolve(userId);
        String name = optionalText(employee.get("name"), 128);
        if (StringUtils.isBlank(name) || !userId.equals(String.valueOf(employee.get("userId")))) {
            CompanyAdminErrors.invalid("通讯录未返回可用的姓名和 User ID");
        }
        CompanyFeishuRepository.Recipient existing = store.findRecipientByKey(app.appId, "user_id", userId);
        long now = System.currentTimeMillis();
        if (existing != null) {
            existing.displayName = name;
            existing.identityStatus = "CONFIRMED";
            existing.source = "CONTACT_LOOKUP";
            existing.updatedAt = now;
            existing.updatedBy = operatorId;
            store.updateRecipient(existing);
            return confirmedRecipientVo(store.findRecipient(existing.id), false);
        }
        CompanyFeishuRepository.Recipient row = new CompanyFeishuRepository.Recipient();
        row.id = ids.getAsLong();
        row.recipientType = "PERSONAL";
        row.receiveIdType = "user_id";
        row.displayName = name;
        row.feishuId = userId;
        row.appId = app.appId;
        row.source = "CONTACT_LOOKUP";
        row.identityStatus = "CONFIRMED";
        row.verifyStatus = "UNVERIFIED";
        row.status = "ENABLED";
        row.createdAt = now;
        row.createdBy = operatorId;
        row.updatedAt = now;
        row.updatedBy = operatorId;
        store.insertRecipient(row);
        return confirmedRecipientVo(store.findRecipient(row.id), true);
    }

    private void requireDashboardView(String dashboardId) {
        try {
            dashboardGuard.requireView(Long.parseLong(dashboardId));
        } catch (NumberFormatException e) {
            CompanyAdminErrors.invalid("dashboard_id 非法");
        }
    }

    private CompanyFeishuRepository.Recipient newRecipient(Map<String, Object> body, long operatorId, boolean creating) {
        CompanyFeishuRepository.AppConfig app = store.findAppConfig();
        if (app == null) {
            CompanyAdminErrors.invalid("请先由系统管理员配置飞书应用");
        }
        String recipientType = requiredEnum(body.get("recipientType"), RECIPIENT_TYPES, "recipientType");
        String receiveIdType = requiredEnum(body.get("receiveIdType"), RECEIVE_ID_TYPES, "receiveIdType");
        if ("PERSONAL".equals(recipientType) && !"user_id".equals(receiveIdType)) {
            CompanyAdminErrors.invalid("个人收件人 receive_id_type 必须是 user_id");
        }
        if ("CHAT".equals(recipientType) && !"chat_id".equals(receiveIdType)) {
            CompanyAdminErrors.invalid("群聊收件人 receive_id_type 必须是 chat_id");
        }
        CompanyFeishuRepository.Recipient row = new CompanyFeishuRepository.Recipient();
        long now = System.currentTimeMillis();
        row.id = creating ? ids.getAsLong() : null;
        row.recipientType = recipientType;
        row.receiveIdType = receiveIdType;
        row.displayName = requiredText(body, "displayName", 128);
        row.feishuId = requiredText(body, "feishuId", 64);
        row.unionId = optionalText(body.get("unionId"), 64);
        row.appId = app.appId;
        row.source = optionalText(body.get("source"), 32);
        if (StringUtils.isBlank(row.source)) {
            row.source = "MANUAL";
        }
        row.identityStatus = creating ? "UNCONFIRMED" : null;
        row.verifyStatus = creating ? "UNVERIFIED" : null;
        row.status = optionalEnum(body.get("status"), STATUSES, "ENABLED");
        row.createdAt = now;
        row.createdBy = operatorId;
        row.updatedAt = now;
        row.updatedBy = operatorId;
        return row;
    }

    private void maybeMapIdentity(Map<String, Object> body, CompanyFeishuRepository.Recipient row, long operatorId) {
        Object raw = body.get("biUserId");
        if (raw == null || StringUtils.isBlank(String.valueOf(raw))) {
            return;
        }
        if (!"PERSONAL".equals(row.recipientType)) {
            CompanyAdminErrors.invalid("只有个人收件人可以绑定 BI 用户");
        }
        long biUserId;
        try {
            biUserId = Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException e) {
            CompanyAdminErrors.invalid("biUserId 非法");
            return;
        }
        if (store.findMapByUser(row.appId, biUserId) != null || store.findMapByRecipient(row.id) != null) {
            CompanyAdminErrors.conflict("身份映射已存在");
        }
        CompanyFeishuRepository.IdentityMap map = new CompanyFeishuRepository.IdentityMap();
        long now = System.currentTimeMillis();
        map.id = ids.getAsLong();
        map.biUserId = biUserId;
        map.recipientId = row.id;
        map.appId = row.appId;
        map.source = "MANUAL";
        map.status = "ENABLED";
        map.createdAt = now;
        map.createdBy = operatorId;
        map.updatedAt = now;
        map.updatedBy = operatorId;
        store.insertIdentityMap(map);
    }

    private CompanyFeishuRepository.Subscription newSubscription(Map<String, Object> body, long operatorId, boolean creating) {
        CompanyFeishuRepository.Subscription row = new CompanyFeishuRepository.Subscription();
        long now = System.currentTimeMillis();
        row.id = creating ? ids.getAsLong() : null;
        row.name = requiredText(body, "name", 128);
        row.dashboardId = requiredText(body, "dashboardId", 64);
        row.chartId = requiredText(body, "chartId", 64);
        row.chartName = optionalText(body.get("chartName"), 256);
        row.titleTemplate = optionalText(body.get("titleTemplate"), 256);
        row.bodyTemplate = optionalText(body.get("bodyTemplate"), 4000);
        row.includeDetailLink = truthy(body.get("includeDetailLink")) ? 1 : 0;
        row.fixedFilterJson = normalizeFilterJson(body.get("fixedFilterJson"));
        row.bizDateRule = optionalText(body.get("bizDateRule"), 64);
        row.scheduleJson = normalizeScheduleJson(body.get("scheduleJson"));
        row.freshnessJson = optionalText(body.get("freshnessJson"), 2000);
        row.status = optionalEnum(body.get("status"), SUB_STATUSES, creating ? "DRAFT" : "DRAFT");
        if ("DELETED".equals(row.status) && creating) {
            CompanyAdminErrors.invalid("不能创建已删除订阅");
        }
        row.createdAt = now;
        row.createdBy = operatorId;
        row.updatedAt = now;
        row.updatedBy = operatorId;
        return row;
    }

    private void replaceRecipients(Long subscriptionId, List<Long> recipientIds, long operatorId) {
        store.deleteSubscriptionRecipients(subscriptionId);
        long now = System.currentTimeMillis();
        for (Long recipientId : recipientIds) {
            store.insertSubscriptionRecipient(ids.getAsLong(), subscriptionId, recipientId, now, operatorId);
        }
    }

    private void validateRecipients(List<Long> recipientIds) {
        if (recipientIds.isEmpty()) {
            CompanyAdminErrors.invalid("至少选择一个收件人");
        }
        for (Long recipientId : recipientIds) {
            CompanyFeishuRepository.Recipient row = store.findRecipient(recipientId);
            if (row == null || "DISABLED".equals(row.status)) {
                CompanyAdminErrors.invalid("收件人不存在或已停用");
            }
        }
    }

    private CompanyFeishuRepository.Recipient requireRecipient(Long id) {
        CompanyFeishuRepository.Recipient row = store.findRecipient(id);
        if (row == null) {
            CompanyAdminErrors.invalid("收件人不存在");
        }
        return row;
    }

    private CompanyFeishuRepository.Subscription requireSubscription(Long id) {
        CompanyFeishuRepository.Subscription row = store.findSubscription(id);
        if (row == null || "DELETED".equals(row.status)) {
            CompanyAdminErrors.invalid("订阅不存在");
        }
        return row;
    }

    private Map<String, Object> recipientVo(CompanyFeishuRepository.Recipient row) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", String.valueOf(row.id));
        vo.put("recipientType", row.recipientType);
        vo.put("receiveIdType", row.receiveIdType);
        vo.put("displayName", row.displayName);
        vo.put("feishuId", row.feishuId);
        vo.put("appId", row.appId);
        vo.put("source", row.source);
        vo.put("identityStatus", StringUtils.defaultIfBlank(row.identityStatus, "UNCONFIRMED"));
        vo.put("verifyStatus", row.verifyStatus);
        vo.put("status", row.status);
        vo.put("lastVerifiedAt", row.lastVerifiedAt);
        return vo;
    }

    private Map<String, Object> subscriptionVo(CompanyFeishuRepository.Subscription row, boolean detail) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", String.valueOf(row.id));
        vo.put("name", row.name);
        vo.put("dashboardId", row.dashboardId);
        vo.put("chartId", row.chartId);
        vo.put("chartName", row.chartName);
        vo.put("status", row.status);
        vo.put("scheduleJson", row.scheduleJson);
        vo.put("freshnessJson", row.freshnessJson);
        vo.put("deliveryLane", StringUtils.defaultIfBlank(row.deliveryLane, "TEST"));
        if (detail) {
            vo.put("titleTemplate", row.titleTemplate);
            vo.put("bodyTemplate", row.bodyTemplate);
            vo.put("includeDetailLink", Integer.valueOf(1).equals(row.includeDetailLink));
            vo.put("fixedFilterJson", row.fixedFilterJson);
            vo.put("recipientIds", store.listRecipientIds(row.id).stream().map(String::valueOf).toList());
        }
        return vo;
    }

    private Map<String, Object> runVo(CompanyFeishuRepository.DeliveryRun run, boolean detail) {
        List<CompanyFeishuRepository.DeliveryTarget> targetRows = store.listTargets(run.id);
        String executionMode = CompanyFeishuExecutionModes.resolve(
                run.executionMode, run.errorSummary, targetRows);
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", String.valueOf(run.id));
        vo.put("subscriptionId", String.valueOf(run.subscriptionId));
        vo.put("triggerType", run.triggerType);
        vo.put("status", run.status);
        vo.put("executionMode", executionMode);
        vo.put("screenshotStatus", run.screenshotStatus);
        vo.put("uploadStatus", run.uploadStatus);
        vo.put("errorSummary", run.errorSummary);
        vo.put("startedAt", run.startedAt);
        vo.put("finishedAt", run.finishedAt);
        vo.put("createdAt", run.createdAt);
        if (detail) {
            List<Map<String, Object>> targets = new ArrayList<>();
            for (CompanyFeishuRepository.DeliveryTarget target : targetRows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", String.valueOf(target.id));
                item.put("recipientId", String.valueOf(target.recipientId));
                item.put("displayName", target.displayName);
                item.put("recipientType", target.recipientType);
                item.put("receiveIdType", target.receiveIdType);
                item.put("status", target.status);
                item.put("executionMode", CompanyFeishuExecutionModes.resolveTarget(
                        executionMode, null, target.errorSummary, target.feishuMessageId));
                item.put("messageId", target.feishuMessageId);
                item.put("retryCount", target.retryCount);
                item.put("errorSummary", target.errorSummary);
                item.put("sentAt", target.sentAt);
                targets.add(item);
            }
            vo.put("targets", targets);
        }
        return vo;
    }

    private Map<String, Object> confirmedRecipientVo(CompanyFeishuRepository.Recipient row, boolean created) {
        Map<String, Object> vo = recipientVo(row);
        vo.put("created", created);
        vo.put("identityJustConfirmed", true);
        vo.put("sendVerified", "VERIFIED".equals(row.verifyStatus));
        return vo;
    }

    private void requireDirectory() {
        if (directory == null) {
            CompanyAdminErrors.invalid("通讯录查询服务未配置");
        }
    }

    private static void rejectSecretFields(Map<String, Object> body) {
        if (body == null) {
            CompanyAdminErrors.invalid("请求体不能为空");
        }
        for (String key : List.of("appSecret", "secret", "token", "accessToken", "tenantAccessToken")) {
            if (body.containsKey(key)) {
                CompanyAdminErrors.invalid("不允许提交密钥字段");
            }
        }
    }

    private static String requiredSecretRef(Object raw) {
        String value = requiredText(Map.of("appSecretRef", raw), "appSecretRef", 256);
        if (!value.matches("^[A-Z][A-Z0-9_]{2,63}$")) {
            CompanyAdminErrors.invalid("app_secret_ref 只能是环境变量名");
        }
        return value;
    }

    private static String requiredText(Map<String, Object> body, String key, int max) {
        String value = optionalText(body.get(key), max);
        if (StringUtils.isBlank(value)) {
            CompanyAdminErrors.invalid(key + " 不能为空");
        }
        if (value.contains(" ") && ("feishuId".equals(key) || "appId".equals(key))) {
            CompanyAdminErrors.invalid(key + " 非法");
        }
        return value;
    }

    private static String optionalText(Object raw, int max) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        if (value.isEmpty() || "null".equals(value)) {
            return null;
        }
        if (value.length() > max) {
            CompanyAdminErrors.invalid("字段超长");
        }
        return value;
    }

    private static String requiredEnum(Object raw, Set<String> allowed, String field) {
        String value = raw == null ? null : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if ("USER_ID".equals(value)) {
            value = "user_id";
        } else if ("CHAT_ID".equals(value)) {
            value = "chat_id";
        } else if (value != null && allowed.contains(value)) {
            return value;
        } else if (value != null && allowed.contains(value.toLowerCase(Locale.ROOT))) {
            return value.toLowerCase(Locale.ROOT);
        }
        if (value == null || !allowed.contains(value) && !allowed.contains(value.toLowerCase(Locale.ROOT))) {
            CompanyAdminErrors.invalid(field + " 非法");
        }
        return allowed.contains(value) ? value : value.toLowerCase(Locale.ROOT);
    }

    private static String optionalEnum(Object raw, Set<String> allowed, String fallback) {
        if (raw == null || StringUtils.isBlank(String.valueOf(raw))) {
            return fallback;
        }
        return requiredEnum(raw, allowed, "status");
    }

    @SuppressWarnings("unchecked")
    private static List<Long> parseRecipientIds(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            CompanyAdminErrors.invalid("recipientIds 不能为空");
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : (List<Object>) raw) {
            try {
                ids.add(Long.parseLong(String.valueOf(item)));
            } catch (NumberFormatException e) {
                CompanyAdminErrors.invalid("recipientIds 非法");
            }
        }
        return ids;
    }

    private static String normalizeFilterJson(Object raw) {
        if (raw == null || StringUtils.isBlank(String.valueOf(raw))) {
            return null;
        }
        String text = raw instanceof String ? (String) raw : writeJson(raw);
        if (text.length() > 8000) {
            CompanyAdminErrors.invalid("fixedFilterJson 过长");
        }
        try {
            JsonNode node = JSON.readTree(text);
            if (!node.isObject()) {
                CompanyAdminErrors.invalid("fixedFilterJson 必须是对象");
            }
            if (node.has("items") && !node.get("items").isArray()) {
                CompanyAdminErrors.invalid("fixedFilterJson.items 必须是数组");
            }
            return JSON.writeValueAsString(node);
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            CompanyAdminErrors.invalid("fixedFilterJson 不是合法 JSON");
            return null;
        }
    }

    private static String normalizeScheduleJson(Object raw) {
        String text = raw == null || StringUtils.isBlank(String.valueOf(raw))
                ? "{\"kind\":\"manual\",\"autoRun\":false,\"timezone\":\"Asia/Shanghai\"}"
                : (raw instanceof String ? (String) raw : writeJson(raw));
        try {
            JsonNode node = JSON.readTree(text);
            if (!node.isObject()) {
                CompanyAdminErrors.invalid("scheduleJson 必须是对象");
            }
            return JSON.writeValueAsString(node);
        } catch (DEException e) {
            throw e;
        } catch (Exception e) {
            CompanyAdminErrors.invalid("scheduleJson 不是合法 JSON");
            return null;
        }
    }

    private static String writeJson(Object raw) {
        try {
            return JSON.writeValueAsString(raw);
        } catch (Exception e) {
            CompanyAdminErrors.invalid("JSON 无法序列化");
            return null;
        }
    }

    private static boolean truthy(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        return raw != null && Set.of("1", "true", "TRUE").contains(String.valueOf(raw));
    }
}
