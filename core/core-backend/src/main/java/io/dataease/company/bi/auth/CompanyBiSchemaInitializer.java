package io.dataease.company.bi.auth;

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

    private static final String SCHEMA_LOCATION = "db/company/company_bi_user.sql";

    private final DataSource dataSource;
    private final CompanyBiBootstrapper bootstrapper;

    @Value("${company.bi.bootstrap-username:admin}")
    private String bootstrapUsername;

    @Value("${company.bi.bootstrap-display-name:系统管理员}")
    private String bootstrapDisplayName;

    @Value("${company.bi.bootstrap-password:}")
    private String bootstrapPassword;

    public CompanyBiSchemaInitializer(DataSource dataSource,
                                      CompanyBiUserService userService,
                                      CompanyPasswordHasher passwordHasher) {
        this.dataSource = dataSource;
        this.bootstrapper = new CompanyBiBootstrapper(userService, passwordHasher);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        applySchema();
        bootstrapper.ensureBootstrapAdmin(bootstrapUsername, bootstrapDisplayName, bootstrapPassword);
    }

    private void applySchema() throws Exception {
        ClassPathResource resource = new ClassPathResource(SCHEMA_LOCATION);
        String sql;
        try (var in = resource.getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (StringUtils.isBlank(sql)) {
            throw new IllegalStateException("Empty schema script: " + SCHEMA_LOCATION);
        }
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
        LogUtil.info("Applied company_bi_user schema if missing");
    }
}
