package io.dataease.company.bi.feishu;

import io.dataease.company.bi.admin.CompanyAdminErrors;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.utils.IDUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Login-capable bindings can only be established by the user administration workflow. */
@Service
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyFeishuIdentityService {
    public static final String VERIFIED = "USER_ADMIN_VERIFIED";
    private final JdbcTemplate jdbc;
    private final CompanyFeishuRepository store;
    private final CompanyFeishuService feishu;
    private final java.util.function.LongSupplier ids;

    @org.springframework.beans.factory.annotation.Autowired
    public CompanyFeishuIdentityService(JdbcTemplate jdbc, CompanyFeishuRepository store, CompanyFeishuService feishu) {
        this(jdbc,store,feishu,IDUtils::snowID);
    }
    CompanyFeishuIdentityService(JdbcTemplate jdbc, CompanyFeishuRepository store, CompanyFeishuService feishu,
                                java.util.function.LongSupplier ids) {
        this.jdbc = jdbc;
        this.store = store;
        this.feishu = feishu;
        this.ids = ids;
    }

    public Map<String,Object> search(String query) {
        var result = new java.util.LinkedHashMap<>(feishu.searchEmployees(query));
        var app = store.findAppConfig();
        if (app == null || !(result.get("matches") instanceof List<?> matches)) return result;
        var occupied = jdbc.queryForList("""
                SELECT r.feishu_id, m.bi_user_id AS user_id, u.username
                FROM company_feishu_identity_map m JOIN company_feishu_recipient r ON r.id=m.recipient_id
                LEFT JOIN company_bi_user u ON u.id=m.bi_user_id WHERE m.app_id=?
                """, app.appId);
        result.put("matches", matches.stream().map(raw -> {
            var item = new java.util.LinkedHashMap<String,Object>();
            if (raw instanceof Map<?,?> row) row.forEach((key,value)->item.put(String.valueOf(key),value));
            occupied.stream().filter(row->java.util.Objects.equals(row.get("feishu_id"),item.get("userId"))).findFirst().ifPresent(row->{
                item.put("boundUserId",String.valueOf(row.get("user_id"))); item.put("boundUsername",row.get("username"));
            });
            return item;
        }).toList());
        return result;
    }

    public Map<String, Object> binding(long userId) {
        var app = store.findAppConfig();
        if (app == null) return Map.of();
        var rows = jdbc.queryForList("""
                SELECT m.id AS bindingId, r.feishu_id AS userId,
                       r.display_name AS name, m.source AS source
                FROM company_feishu_identity_map m JOIN company_feishu_recipient r ON r.id=m.recipient_id
                WHERE m.bi_user_id=? AND m.app_id=? AND m.status='ENABLED'
                """, userId, app.appId);
        if (!rows.isEmpty()) rows.get(0).put("bindingId", String.valueOf(rows.get(0).get("bindingId")));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** Called inside the same transaction as user creation/edit. Empty means explicit unbind. */
    @Transactional
    public void bind(long userId, String feishuUserId, long operator) {
        if (feishuUserId == null) return;
        jdbc.queryForList("SELECT id FROM company_bi_user WHERE id=? FOR UPDATE", userId);
        var app = store.findAppConfig();
        if (app == null) {
            if (feishuUserId.isBlank()) return;
            CompanyAdminErrors.invalid("请先配置飞书应用连接");
        }
        var old = store.findMapByUser(app.appId, userId);
        boolean sameIdentity = false;
        if (old != null && !feishuUserId.isBlank()) {
            var recipient = store.findRecipient(old.recipientId);
            sameIdentity = recipient != null && feishuUserId.equals(recipient.feishuId);
            if (sameIdentity && VERIFIED.equals(old.source)) return;
        }
        if (old != null && !sameIdentity) {
            // Retain subscriptions and their recipient identity; never silently retarget delivery.
            var used = jdbc.queryForList("""
                    SELECT s.name FROM company_feishu_subscription_recipient sr
                    JOIN company_feishu_subscription s ON s.id=sr.subscription_id
                    WHERE sr.recipient_id=? AND s.status<>'DELETED' ORDER BY s.id LIMIT 5
                    """, String.class, old.recipientId);
            if (!used.isEmpty()) CompanyAdminErrors.invalid("请先从订阅「" + String.join("、", used) + "」中移除该收件人，再更换或解除绑定");
        }
        Long recipientId = null;
        if (!feishuUserId.isBlank()) {
            feishu.confirmEmployee(Map.of("userId", feishuUserId.trim()), operator);
            var recipient = store.findRecipientByKey(app.appId, "user_id", feishuUserId.trim());
            var occupied = store.findMapByRecipient(recipient.id);
            if (occupied != null && !occupied.biUserId.equals(userId)) CompanyAdminErrors.conflict("该飞书账号已绑定其他 BI 用户");
            recipientId = recipient.id;
        }
        jdbc.update("DELETE FROM company_feishu_identity_map WHERE bi_user_id=? AND app_id=?", userId, app.appId);
        if (recipientId != null) {
            var row = new CompanyFeishuRepository.IdentityMap();
            row.id = ids.getAsLong(); row.biUserId = userId; row.recipientId = recipientId;
            row.appId = app.appId; row.source = VERIFIED; row.status = "ENABLED";
            row.createdAt = row.updatedAt = System.currentTimeMillis(); row.createdBy = row.updatedBy = operator;
            try { store.insertIdentityMap(row); }
            catch (org.springframework.dao.DuplicateKeyException e) { CompanyAdminErrors.conflict("该飞书账号已绑定其他 BI 用户，请刷新后重试"); }
        }
    }

    public List<Map<String, Object>> boundRecipients() {
        var app = store.findAppConfig();
        if (app == null) return List.of();
        var rows = jdbc.queryForList("""
                SELECT r.id AS recipientId, u.id AS biUserId,
                       u.username, u.display_name AS biName, r.display_name AS feishuName
                FROM company_feishu_identity_map m JOIN company_bi_user u ON u.id=m.bi_user_id
                JOIN company_feishu_recipient r ON r.id=m.recipient_id
                WHERE m.app_id=? AND m.status='ENABLED' AND u.status='ENABLED' AND r.status='ENABLED'
                ORDER BY u.username
                """, app.appId);
        rows.forEach(row -> {
            row.put("recipientId", String.valueOf(row.get("recipientId")));
            row.put("biUserId", String.valueOf(row.get("biUserId")));
        });
        return rows;
    }

    public CompanyFeishuRepository.IdentityMap loginBinding(String appId, String userId) {
        var recipient = store.findRecipientByKey(appId, "user_id", userId);
        var map = recipient == null ? null : store.findMapByRecipient(recipient.id);
        if (map == null || !VERIFIED.equals(map.source) || !"ENABLED".equals(map.status))
            CompanyAdminErrors.invalid("此飞书账号尚未绑定 BI 用户，请联系管理员在用户管理中绑定");
        return map;
    }

    public boolean sessionActive(long bindingId, long userId, String appId) {
        var app = store.findAppConfig();
        if (app == null || !appId.equals(app.appId)) return false;
        var count = jdbc.queryForObject("SELECT COUNT(*) FROM company_feishu_identity_map WHERE id=? AND bi_user_id=? AND app_id=? AND source=? AND status='ENABLED'",
                Long.class, bindingId, userId, appId, VERIFIED);
        return count != null && count == 1;
    }
}
