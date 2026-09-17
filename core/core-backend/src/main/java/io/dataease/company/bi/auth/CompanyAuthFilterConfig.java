package io.dataease.company.bi.auth;

import io.dataease.company.bi.admin.CompanyAdminApiFilter;
import io.dataease.company.bi.permission.CompanyCapability;
import io.dataease.company.bi.permission.CompanyCapabilityPathFilter;
import io.dataease.company.bi.permission.CompanyDatasetDesignFilter;
import io.dataease.company.bi.permission.CompanyDatasetDesignGuard;
import io.dataease.company.bi.permission.PermissionDecisionService;
import io.dataease.company.bi.feishu.CompanyFeishuProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(CompanyFeishuProperties.class)
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyAuthFilterConfig {

    @Bean
    public FilterRegistrationBean<CompanyUserStatusFilter> companyUserStatusFilter(CompanyUserStatusGuard statusGuard) {
        FilterRegistrationBean<CompanyUserStatusFilter> filter = new FilterRegistrationBean<>();
        filter.setName("companyUserStatusFilter");
        filter.setFilter(new CompanyUserStatusFilter(statusGuard));
        filter.addUrlPatterns("/*");
        filter.setOrder(6);
        return filter;
    }

    @Bean
    public FilterRegistrationBean<CompanyDatasetDesignFilter> companyDatasetDesignFilter(CompanyDatasetDesignGuard guard) {
        FilterRegistrationBean<CompanyDatasetDesignFilter> filter = new FilterRegistrationBean<>();
        filter.setName("companyDatasetDesignFilter");
        filter.setFilter(new CompanyDatasetDesignFilter(guard));
        filter.addUrlPatterns("/*");
        filter.setOrder(7);
        return filter;
    }

    @Bean
    public FilterRegistrationBean<CompanyCapabilityPathFilter> companyDatasourceAdminFilter(
            PermissionDecisionService permissionDecisionService) {
        FilterRegistrationBean<CompanyCapabilityPathFilter> filter = new FilterRegistrationBean<>();
        filter.setName("companyDatasourceAdminFilter");
        filter.setFilter(new CompanyCapabilityPathFilter(permissionDecisionService,
                CompanyCapability.DATASOURCE_ACCESS, List.of("/datasource", "/engine")));
        filter.addUrlPatterns("/*");
        filter.setOrder(8);
        return filter;
    }

    @Bean
    public FilterRegistrationBean<CompanyCapabilityPathFilter> companyEtlAdminFilter(
            PermissionDecisionService permissionDecisionService) {
        FilterRegistrationBean<CompanyCapabilityPathFilter> filter = new FilterRegistrationBean<>();
        filter.setName("companyEtlAdminFilter");
        filter.setFilter(new CompanyCapabilityPathFilter(permissionDecisionService,
                CompanyCapability.ETL_ACCESS, List.of("/sync")));
        filter.addUrlPatterns("/*");
        filter.setOrder(9);
        return filter;
    }

    @Bean
    public FilterRegistrationBean<CompanyAdminApiFilter> companyAdminApiFilter(
            PermissionDecisionService permissionDecisionService) {
        FilterRegistrationBean<CompanyAdminApiFilter> filter = new FilterRegistrationBean<>();
        filter.setName("companyAdminApiFilter");
        filter.setFilter(new CompanyAdminApiFilter(permissionDecisionService));
        filter.addUrlPatterns("/*");
        filter.setOrder(10);
        return filter;
    }
}
