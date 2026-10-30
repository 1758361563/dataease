package io.dataease.company.bi.auth;

import io.dataease.company.bi.permission.CompanyDashboardAclBootstrapper;
import io.dataease.company.bi.permission.CompanyDatasetAclBootstrapper;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyBiSchemaInitializer implements ApplicationRunner {

    private static final String[] SCHEMA_LOCATIONS = {
            "db/company/company_bi_user.sql",
            "db/company/company_bi_group.sql",
            "db/company/company_bi_group_member.sql",
            "db/company/company_bi_resource_acl.sql",
            "db/company/company_bi_audit_log.sql",
            "db/company/company_bi_compat_seed.sql",
            "db/company/company_feishu_app_config.sql",
            "db/company/company_integration_connection.sql",
            "db/company/company_feishu_recipient.sql",
            "db/company/company_feishu_identity_map.sql",
            "db/company/company_feishu_subscription.sql",
            "db/company/company_feishu_subscription_recipient.sql",
            "db/company/company_feishu_delivery_run.sql",
            "db/company/company_feishu_delivery_target.sql",
            "db/company/company_feishu_source.sql",
            "db/company/company_etl_setting.sql",
            "db/company/company_etl_task.sql",
            "db/company/company_etl_package.sql",
            "db/company/company_etl_package_file.sql",
            "db/company/company_etl_batch.sql",
            "db/company/company_data_update_task.sql",
            "db/company/company_data_update_run.sql",
            "db/company/company_data_update_guard.sql",
            "db/company/company_data_update_ddl.sql",
            "db/company/company_data_update_claim.sql",
            "db/company/company_data_update_group.sql",
            "db/company/company_data_update_member.sql",
            "db/company/company_data_update_group_run.sql",
            "db/company/company_data_update_group_node.sql"
    };

    private final DataSource dataSource;
    private final CompanyBiBootstrapper bootstrapper;
    private final CompanyDatasetAclBootstrapper datasetAclBootstrapper;
    private final CompanyDashboardAclBootstrapper dashboardAclBootstrapper;

    @Value("${company.bi.bootstrap-username:admin}")
    private String bootstrapUsername;

    @Value("${company.bi.bootstrap-display-name:系统管理员}")
    private String bootstrapDisplayName;

    @Value("${company.bi.bootstrap-password:}")
    private String bootstrapPassword;

    public CompanyBiSchemaInitializer(DataSource dataSource,
                                      CompanyBiUserService userService,
                                      CompanyPasswordHasher passwordHasher,
                                      CompanyDatasetAclBootstrapper datasetAclBootstrapper,
                                      CompanyDashboardAclBootstrapper dashboardAclBootstrapper) {
        this.dataSource = dataSource;
        this.bootstrapper = new CompanyBiBootstrapper(userService, passwordHasher);
        this.datasetAclBootstrapper = datasetAclBootstrapper;
        this.dashboardAclBootstrapper = dashboardAclBootstrapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        applySchema();
        bootstrapper.ensureBootstrapAdmin(bootstrapUsername, bootstrapDisplayName, bootstrapPassword);
        datasetAclBootstrapper.seedBootstrapAdminExistingDatasets();
        dashboardAclBootstrapper.seedBootstrapAdminExistingDashboards();
    }

    private void applySchema() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            for (String location : SCHEMA_LOCATIONS) {
                ClassPathResource resource = new ClassPathResource(location);
                String sql;
                try (var in = resource.getInputStream()) {
                    sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (StringUtils.isBlank(sql)) {
                    throw new IllegalStateException("Empty schema script: " + location);
                }
                stmt.execute(sql);
                LogUtil.info("Applied company schema if missing: " + location);
            }
            ensureFeishuExecutionMode(conn);
            ensureFeishuDeliveryLane(conn);
        }
    }

    private void ensureFeishuDeliveryLane(Connection conn) throws Exception {
        boolean present;
        try (PreparedStatement ps = conn.prepareStatement(
                """
                SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'company_feishu_subscription'
                   AND COLUMN_NAME = 'delivery_lane'
                """)) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                present = rs.getInt(1) > 0;
            }
        }
        if (present) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    ALTER TABLE company_feishu_subscription
                      ADD COLUMN delivery_lane VARCHAR(16) NOT NULL DEFAULT 'TEST'
                      COMMENT 'TEST never live-sends. PROD may live-send only when service dry-run is off.'
                      AFTER status
                    """);
            LogUtil.info("Added company_feishu_subscription.delivery_lane");
        }
    }

    private void ensureFeishuExecutionMode(Connection conn) throws Exception {
        boolean present;
        try (PreparedStatement ps = conn.prepareStatement(
                """
                SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'company_feishu_delivery_run'
                   AND COLUMN_NAME = 'execution_mode'
                """)) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                present = rs.getInt(1) > 0;
            }
        }
        try (Statement stmt = conn.createStatement()) {
            if (!present) {
                stmt.execute("""
                        ALTER TABLE company_feishu_delivery_run
                          ADD COLUMN execution_mode VARCHAR(16) NULL COMMENT 'DRY_RUN / LIVE / UNKNOWN' AFTER status
                        """);
                LogUtil.info("Added company_feishu_delivery_run.execution_mode");
            }
            stmt.execute("""
                    UPDATE company_feishu_delivery_run r
                       SET execution_mode = 'DRY_RUN'
                     WHERE execution_mode IS NULL
                       AND (
                         LOWER(IFNULL(error_summary, '')) LIKE '%dry-run%'
                         OR EXISTS (
                           SELECT 1 FROM company_feishu_delivery_target t
                            WHERE t.run_id = r.id AND LOWER(IFNULL(t.error_summary, '')) LIKE '%dry-run%'
                         )
                       )
                    """);
            stmt.execute("""
                    UPDATE company_feishu_delivery_run r
                       SET execution_mode = 'LIVE'
                     WHERE execution_mode IS NULL
                       AND EXISTS (
                         SELECT 1 FROM company_feishu_delivery_target t
                          WHERE t.run_id = r.id
                            AND t.feishu_message_id IS NOT NULL
                            AND t.feishu_message_id <> ''
                       )
                    """);
            stmt.execute("""
                    UPDATE company_feishu_delivery_run
                       SET execution_mode = 'UNKNOWN'
                     WHERE execution_mode IS NULL
                    """);
        }
    }
}
