package io.dataease.company.bi.permission;

import io.dataease.auth.bo.TokenUserBO;
import io.dataease.company.bi.admin.CompanyHttpDeny;
import io.dataease.exception.DEException;
import io.dataease.utils.AuthUtils;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

public class CompanyCapabilityPathFilter implements Filter {

    private final PermissionDecisionService permissionDecisionService;
    private final CompanyCapability capability;
    private final List<String> prefixes;

    public CompanyCapabilityPathFilter(PermissionDecisionService permissionDecisionService,
                                       CompanyCapability capability,
                                       List<String> prefixes) {
        this.permissionDecisionService = permissionDecisionService;
        this.capability = capability;
        this.prefixes = prefixes;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        if (!matches(request.getServletPath(), request.getRequestURI())) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }
        try {
            TokenUserBO user = AuthUtils.getUser();
            if (user == null || user.getUserId() == null
                    || !permissionDecisionService.hasCapability(user.getUserId(), capability)) {
                CompanyDatasetDesignGuardDeny.deny();
            }
        } catch (DEException e) {
            CompanyHttpDeny.writeForbidden((HttpServletResponse) servletResponse, e);
            return;
        }
        filterChain.doFilter(servletRequest, servletResponse);
    }

    public boolean matches(String servletPath, String requestUri) {
        return matchesPath(CompanyDatasetDesignFilter.stripContext(CompanyDatasetDesignFilter.stripQuery(servletPath)))
                || matchesPath(CompanyDatasetDesignFilter.stripContext(CompanyDatasetDesignFilter.stripQuery(requestUri)));
    }

    private boolean matchesPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (String prefix : prefixes) {
            if (path.equals(prefix) || path.startsWith(prefix + "/") || path.startsWith(prefix + "?")) {
                return true;
            }
        }
        return false;
    }

    static final class CompanyDatasetDesignGuardDeny {
        private CompanyDatasetDesignGuardDeny() {
        }

        static void deny() {
            io.dataease.company.bi.admin.CompanyAdminErrors.deny();
        }
    }
}
