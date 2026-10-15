package io.dataease.company.bi.feishu;

import io.dataease.company.bi.auth.CompanyAuthSupport;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyFeishuJdbcStore implements CompanyFeishuRepository {

    private final JdbcTemplate jdbc;

    public CompanyFeishuJdbcStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void ensureIdentityStatusColumn() {
        // Brand-new metadata database: the table does not exist yet. CompanyBiSchemaInitializer (an
        // ApplicationRunner, i.e. it runs AFTER bean creation) creates it WITH identity_status, so there
        // is nothing to add. Only a legacy table that predates the column needs the ALTER below.
        Integer tableExists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'company_feishu_recipient'
                """, Integer.class);
        if (tableExists == null || tableExists == 0) {
            return;
        }
        Integer present = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'company_feishu_recipient'
                   AND COLUMN_NAME = 'identity_status'
                """, Integer.class);
        if (present == null || present == 0) {
            jdbc.execute("""
                    ALTER TABLE company_feishu_recipient
                      ADD COLUMN identity_status VARCHAR(16) NOT NULL DEFAULT 'UNCONFIRMED'
                      COMMENT 'UNCONFIRMED / CONFIRMED. Search-confirm only; not send-verified' AFTER source
                    """);
        }
    }

    @Override
    public AppConfig findAppConfig() {
        List<AppConfig> rows = jdbc.query(
                "SELECT * FROM company_feishu_app_config ORDER BY id LIMIT 1", appConfigMapper());
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public AppConfig findAppConfigByAppId(String appId) {
        List<AppConfig> rows = jdbc.query(
                "SELECT * FROM company_feishu_app_config WHERE app_id = ?", appConfigMapper(), appId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void insertAppConfig(AppConfig row) {
        jdbc.update("""
                INSERT INTO company_feishu_app_config
                  (id, app_id, app_secret_ref, status, last_verified_at, last_verify_error,
                   created_at, created_by, updated_at, updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                row.id, row.appId, row.appSecretRef, row.status, row.lastVerifiedAt, row.lastVerifyError,
                row.createdAt, row.createdBy, row.updatedAt, row.updatedBy);
    }

    @Override
    public void updateAppConfig(AppConfig row) {
        jdbc.update("""
                UPDATE company_feishu_app_config
                   SET app_id=?, app_secret_ref=?, status=?, last_verified_at=?, last_verify_error=?,
                       updated_at=?, updated_by=?
                 WHERE id=?
                """,
                row.appId, row.appSecretRef, row.status, row.lastVerifiedAt, row.lastVerifyError,
                row.updatedAt, row.updatedBy, row.id);
    }

    @Override
    public List<Recipient> listRecipients() {
        return jdbc.query("SELECT * FROM company_feishu_recipient ORDER BY id", recipientMapper());
    }

    @Override
    public Recipient findRecipient(Long id) {
        List<Recipient> rows = jdbc.query(
                "SELECT * FROM company_feishu_recipient WHERE id = ?", recipientMapper(), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public Recipient findRecipientByKey(String appId, String receiveIdType, String feishuId) {
        List<Recipient> rows = jdbc.query(
                "SELECT * FROM company_feishu_recipient WHERE app_id=? AND receive_id_type=? AND feishu_id=?",
                recipientMapper(), appId, receiveIdType, feishuId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void insertRecipient(Recipient row) {
        jdbc.update("""
                INSERT INTO company_feishu_recipient
                  (id, recipient_type, receive_id_type, display_name, feishu_id, union_id, app_id, source,
                   identity_status, verify_status, status, last_verified_at, created_at, created_by, updated_at, updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                row.id, row.recipientType, row.receiveIdType, row.displayName, row.feishuId, row.unionId,
                row.appId, row.source, row.identityStatus, row.verifyStatus, row.status, row.lastVerifiedAt,
                row.createdAt, row.createdBy, row.updatedAt, row.updatedBy);
    }

    @Override
    public void updateRecipient(Recipient row) {
        jdbc.update("""
                UPDATE company_feishu_recipient
                   SET recipient_type=?, receive_id_type=?, display_name=?, feishu_id=?, union_id=?, app_id=?,
                       source=?, identity_status=?, verify_status=?, status=?, last_verified_at=?, updated_at=?, updated_by=?
                 WHERE id=?
                """,
                row.recipientType, row.receiveIdType, row.displayName, row.feishuId, row.unionId, row.appId,
                row.source, row.identityStatus, row.verifyStatus, row.status, row.lastVerifiedAt, row.updatedAt,
                row.updatedBy, row.id);
    }

    @Override
    public IdentityMap findMapByRecipient(Long recipientId) {
        List<IdentityMap> rows = jdbc.query(
                "SELECT * FROM company_feishu_identity_map WHERE recipient_id=?", identityMapper(), recipientId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public IdentityMap findMapByUser(String appId, Long biUserId) {
        List<IdentityMap> rows = jdbc.query(
                "SELECT * FROM company_feishu_identity_map WHERE app_id=? AND bi_user_id=?",
                identityMapper(), appId, biUserId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void insertIdentityMap(IdentityMap row) {
        jdbc.update("""
                INSERT INTO company_feishu_identity_map
                  (id, bi_user_id, recipient_id, app_id, source, status, created_at, created_by, updated_at, updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                row.id, row.biUserId, row.recipientId, row.appId, row.source, row.status,
                row.createdAt, row.createdBy, row.updatedAt, row.updatedBy);
    }

    @Override
    public List<Subscription> listSubscriptions() {
        return jdbc.query(
                "SELECT * FROM company_feishu_subscription WHERE status <> 'DELETED' ORDER BY id DESC",
                subscriptionMapper());
    }

    @Override
    public Subscription findSubscription(Long id) {
        List<Subscription> rows = jdbc.query(
                "SELECT * FROM company_feishu_subscription WHERE id=?", subscriptionMapper(), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void insertSubscription(Subscription row) {
        jdbc.update("""
                INSERT INTO company_feishu_subscription
                  (id, name, dashboard_id, chart_id, chart_name, title_template, body_template, include_detail_link,
                   fixed_filter_json, biz_date_rule, schedule_json, freshness_json, status,
                   created_at, created_by, updated_at, updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                row.id, row.name, row.dashboardId, row.chartId, row.chartName, row.titleTemplate, row.bodyTemplate,
                row.includeDetailLink == null ? 0 : row.includeDetailLink, row.fixedFilterJson, row.bizDateRule,
                row.scheduleJson, row.freshnessJson, row.status, row.createdAt, row.createdBy, row.updatedAt,
                row.updatedBy);
    }

    @Override
    public void updateSubscription(Subscription row) {
        jdbc.update("""
                UPDATE company_feishu_subscription
                   SET name=?, dashboard_id=?, chart_id=?, chart_name=?, title_template=?, body_template=?,
                       include_detail_link=?, fixed_filter_json=?, biz_date_rule=?, schedule_json=?, freshness_json=?,
                       status=?, updated_at=?, updated_by=?
                 WHERE id=?
                """,
                row.name, row.dashboardId, row.chartId, row.chartName, row.titleTemplate, row.bodyTemplate,
                row.includeDetailLink == null ? 0 : row.includeDetailLink, row.fixedFilterJson, row.bizDateRule,
                row.scheduleJson, row.freshnessJson, row.status, row.updatedAt, row.updatedBy, row.id);
    }

    @Override
    public List<Long> listRecipientIds(Long subscriptionId) {
        return jdbc.query(
                "SELECT recipient_id FROM company_feishu_subscription_recipient WHERE subscription_id=? ORDER BY id",
                (rs, i) -> rs.getLong(1), subscriptionId);
    }

    @Override
    public void deleteSubscriptionRecipients(Long subscriptionId) {
        jdbc.update("DELETE FROM company_feishu_subscription_recipient WHERE subscription_id=?", subscriptionId);
    }

    @Override
    public void insertSubscriptionRecipient(Long id, Long subscriptionId, Long recipientId, long createdAt, Long createdBy) {
        jdbc.update("""
                INSERT INTO company_feishu_subscription_recipient
                  (id, subscription_id, recipient_id, created_at, created_by)
                VALUES (?,?,?,?,?)
                """, id, subscriptionId, recipientId, createdAt, createdBy);
    }

    @Override
    public List<DeliveryRun> listRuns(Long subscriptionId, int limit) {
        return jdbc.query(
                "SELECT * FROM company_feishu_delivery_run WHERE subscription_id=? ORDER BY created_at DESC LIMIT ?",
                runMapper(), subscriptionId, limit);
    }

    @Override
    public DeliveryRun findRun(Long id) {
        List<DeliveryRun> rows = jdbc.query(
                "SELECT * FROM company_feishu_delivery_run WHERE id=?", runMapper(), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<DeliveryTarget> listTargets(Long runId) {
        return jdbc.query("""
                SELECT t.*, r.display_name, r.recipient_type
                  FROM company_feishu_delivery_target t
                  JOIN company_feishu_recipient r ON r.id = t.recipient_id
                 WHERE t.run_id=?
                 ORDER BY t.id
                """, targetMapper(), runId);
    }

    private static RowMapper<AppConfig> appConfigMapper() {
        return (rs, i) -> {
            AppConfig row = new AppConfig();
            row.id = rs.getLong("id");
            row.appId = rs.getString("app_id");
            row.appSecretRef = rs.getString("app_secret_ref");
            row.status = rs.getString("status");
            row.lastVerifiedAt = nullableLong(rs, "last_verified_at");
            row.lastVerifyError = rs.getString("last_verify_error");
            row.createdAt = nullableLong(rs, "created_at");
            row.createdBy = nullableLong(rs, "created_by");
            row.updatedAt = nullableLong(rs, "updated_at");
            row.updatedBy = nullableLong(rs, "updated_by");
            return row;
        };
    }

    private static RowMapper<Recipient> recipientMapper() {
        return (rs, i) -> {
            Recipient row = new Recipient();
            row.id = rs.getLong("id");
            row.recipientType = rs.getString("recipient_type");
            row.receiveIdType = rs.getString("receive_id_type");
            row.displayName = rs.getString("display_name");
            row.feishuId = rs.getString("feishu_id");
            row.unionId = rs.getString("union_id");
            row.appId = rs.getString("app_id");
            row.source = rs.getString("source");
            row.identityStatus = rs.getString("identity_status");
            if (row.identityStatus == null || row.identityStatus.isBlank()) {
                row.identityStatus = "UNCONFIRMED";
            }
            row.verifyStatus = rs.getString("verify_status");
            row.status = rs.getString("status");
            row.lastVerifiedAt = nullableLong(rs, "last_verified_at");
            row.createdAt = nullableLong(rs, "created_at");
            row.createdBy = nullableLong(rs, "created_by");
            row.updatedAt = nullableLong(rs, "updated_at");
            row.updatedBy = nullableLong(rs, "updated_by");
            return row;
        };
    }

    private static RowMapper<IdentityMap> identityMapper() {
        return (rs, i) -> {
            IdentityMap row = new IdentityMap();
            row.id = rs.getLong("id");
            row.biUserId = rs.getLong("bi_user_id");
            row.recipientId = rs.getLong("recipient_id");
            row.appId = rs.getString("app_id");
            row.source = rs.getString("source");
            row.status = rs.getString("status");
            return row;
        };
    }

    private static RowMapper<Subscription> subscriptionMapper() {
        return (rs, i) -> {
            Subscription row = new Subscription();
            row.id = rs.getLong("id");
            row.name = rs.getString("name");
            row.dashboardId = rs.getString("dashboard_id");
            row.chartId = rs.getString("chart_id");
            row.chartName = rs.getString("chart_name");
            row.titleTemplate = rs.getString("title_template");
            row.bodyTemplate = rs.getString("body_template");
            row.includeDetailLink = rs.getInt("include_detail_link");
            row.fixedFilterJson = rs.getString("fixed_filter_json");
            row.bizDateRule = rs.getString("biz_date_rule");
            row.scheduleJson = rs.getString("schedule_json");
            row.freshnessJson = rs.getString("freshness_json");
            row.status = rs.getString("status");
            row.deliveryLane = rs.getString("delivery_lane");
            row.createdAt = nullableLong(rs, "created_at");
            row.createdBy = nullableLong(rs, "created_by");
            row.updatedAt = nullableLong(rs, "updated_at");
            row.updatedBy = nullableLong(rs, "updated_by");
            return row;
        };
    }

    private static RowMapper<DeliveryRun> runMapper() {
        return (rs, i) -> {
            DeliveryRun row = new DeliveryRun();
            row.id = rs.getLong("id");
            row.subscriptionId = rs.getLong("subscription_id");
            row.triggerType = rs.getString("trigger_type");
            row.plannedAt = nullableLong(rs, "planned_at");
            row.operatorUserId = nullableLong(rs, "operator_user_id");
            row.status = rs.getString("status");
            row.executionMode = rs.getString("execution_mode");
            row.screenshotStatus = rs.getString("screenshot_status");
            row.uploadStatus = rs.getString("upload_status");
            row.imageKey = rs.getString("image_key");
            row.errorSummary = rs.getString("error_summary");
            row.startedAt = nullableLong(rs, "started_at");
            row.finishedAt = nullableLong(rs, "finished_at");
            row.createdAt = nullableLong(rs, "created_at");
            return row;
        };
    }

    private static RowMapper<DeliveryTarget> targetMapper() {
        return (rs, i) -> {
            DeliveryTarget row = new DeliveryTarget();
            row.id = rs.getLong("id");
            row.runId = rs.getLong("run_id");
            row.recipientId = rs.getLong("recipient_id");
            row.receiveIdType = rs.getString("receive_id_type");
            row.feishuId = rs.getString("feishu_id");
            row.feishuMessageId = rs.getString("feishu_message_id");
            row.status = rs.getString("status");
            row.retryCount = rs.getInt("retry_count");
            row.errorSummary = rs.getString("error_summary");
            row.sentAt = nullableLong(rs, "sent_at");
            row.displayName = rs.getString("display_name");
            row.recipientType = rs.getString("recipient_type");
            return row;
        };
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
