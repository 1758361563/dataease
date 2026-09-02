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
            "db/company/company_bi_compat_seed.sql"
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
        }
    }
}
