package io.dataease.company.bi.permission;

import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import io.dataease.utils.LogUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public class CompanyDatasetDesignFilter implements Filter {

    static final Set<String> DESIGN_EXACT = Set.of(
            "/datasetData/previewData",
            "/datasetData/previewSql",
            "/datasetData/previewSqlCheck",
            "/datasetData/tableField",
            "/datasetData/enumValueDs",
            "/datasetData/getDatasetCount",
            "/datasetData/getDatasetTotal",
            "/datasetTree/save",
            "/datasetTree/create",
            "/datasetTree/rename",
            "/datasetTree/move",
            "/datasetTree/exportDataset",
            "/datasetField/save"
    );

    static final List<String> DESIGN_PREFIX = List.of(
            "/datasetTree/delete/",
            "/datasetTree/perDelete/",
            "/datasetTree/get/",
            "/datasetField/delete/",
            "/datasetField/deleteByChartId/"
    );

    private final CompanyDatasetDesignGuard guard;

    public CompanyDatasetDesignFilter(CompanyDatasetDesignGuard guard) {
        this.guard = guard;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        if (!isDesignEndpoint(request.getServletPath(), request.getRequestURI())) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }
        try {
            guard.requireDatasetDesign();
        } catch (DEException e) {
            LogUtil.error(e.getMessage(), e);
            HttpServletResponse response = (HttpServletResponse) servletResponse;
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            int code = e.getCode() == 0 ? ResultCode.PERMISSION_NO_ACCESS.code() : e.getCode();
            String msg = e.getMessage() == null ? ResultCode.PERMISSION_NO_ACCESS.message() : e.getMessage();
            response.getWriter().write("{\"code\":" + code + ",\"msg\":\"" + jsonEscape(msg) + "\",\"data\":null}");
            return;
        }
        filterChain.doFilter(servletRequest, servletResponse);
    }

    static boolean isDesignEndpoint(String servletPath, String requestUri) {
        if (matches(stripContext(stripQuery(servletPath)))) {
            return true;
        }
        String uri = stripContext(stripQuery(requestUri));
        if (matches(uri)) {
            return true;
        }
        if (requestUri == null) {
            return false;
        }
        String raw = stripQuery(requestUri);
        for (String exact : DESIGN_EXACT) {
            if (raw.endsWith(exact)) {
                return true;
            }
        }
        for (String prefix : DESIGN_PREFIX) {
            if (raw.contains(prefix)) {
                return true;
            }
        }
        return false;
    }

    static boolean matches(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (DESIGN_EXACT.contains(path)) {
            return true;
        }
        for (String prefix : DESIGN_PREFIX) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static String stripQuery(String path) {
        if (path == null) {
            return "";
        }
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    static String stripContext(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        for (String ctx : List.of("/de2api", "/casbi/de2api", "/oidcbi/de2api")) {
            if (path.startsWith(ctx)) {
                return path.substring(ctx.length());
            }
        }
        return path;
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
