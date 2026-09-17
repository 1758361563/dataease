package io.dataease.company.bi.admin;

import io.dataease.auth.bo.TokenUserBO;
import io.dataease.company.bi.permission.CompanyCapability;
import io.dataease.company.bi.permission.CompanyDatasetDesignFilter;
import io.dataease.company.bi.permission.PermissionDecisionService;
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
import java.util.LinkedHashMap;
import java.util.Map;

public class CompanyAdminApiFilter implements Filter {

    private static final Map<String, CompanyCapability> PREFIXES = new LinkedHashMap<>();

    static {
        PREFIXES.put("/company/bi/admin/users", CompanyCapability.USER_MANAGE);
        PREFIXES.put("/company/bi/admin/groups", CompanyCapability.GROUP_MANAGE);
        PREFIXES.put("/company/bi/admin/permissions", CompanyCapability.ACL_MANAGE);
        PREFIXES.put("/company/bi/admin/audit", CompanyCapability.AUDIT_VIEW);
        PREFIXES.put("/company/bi/feishu", CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
    }

    private final PermissionDecisionService permissionDecisionService;

    public CompanyAdminApiFilter(PermissionDecisionService permissionDecisionService) {
        this.permissionDecisionService = permissionDecisionService;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        CompanyCapability required = requiredCapability(request.getServletPath(), request.getRequestURI());
        if (required == null) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }
        try {
            TokenUserBO user = AuthUtils.getUser();
            if (user == null || user.getUserId() == null
                    || !permissionDecisionService.hasCapability(user.getUserId(), required)) {
                CompanyAdminErrors.deny();
            }
        } catch (DEException e) {
            CompanyHttpDeny.writeForbidden((HttpServletResponse) servletResponse, e);
            return;
        }
        filterChain.doFilter(servletRequest, servletResponse);
    }

    public static CompanyCapability requiredCapability(String servletPath, String requestUri) {
        CompanyCapability fromServlet = match(CompanyDatasetDesignFilter.stripContext(
                CompanyDatasetDesignFilter.stripQuery(servletPath)));
        if (fromServlet != null) {
            return fromServlet;
        }
        return match(CompanyDatasetDesignFilter.stripContext(CompanyDatasetDesignFilter.stripQuery(requestUri)));
    }

    private static CompanyCapability match(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, CompanyCapability> entry : PREFIXES.entrySet()) {
            String prefix = entry.getKey();
            if (path.equals(prefix) || path.startsWith(prefix + "/") || path.startsWith(prefix + "?")) {
                return entry.getValue();
            }
        }
        return null;
    }
}
