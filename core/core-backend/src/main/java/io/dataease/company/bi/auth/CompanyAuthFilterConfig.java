package io.dataease.company.bi.auth;

import io.dataease.company.bi.permission.CompanyDatasetDesignFilter;
import io.dataease.company.bi.permission.CompanyDatasetDesignGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
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
}
