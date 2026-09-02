package io.dataease.company.bi.admin;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

public record CompanyAdminAuditContext(long operatorUserId, String ip, String requestId) {

    public static CompanyAdminAuditContext from(HttpServletRequest request, long operatorUserId) {
        String ip = request == null ? null : firstNonBlank(request.getHeader("X-Forwarded-For"), request.getRemoteAddr());
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        String requestId = request == null ? null : firstNonBlank(
                request.getHeader("X-Request-Id"),
                request.getHeader("X-Request-ID"),
                request.getHeader("DE-REQUEST-ID"));
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return new CompanyAdminAuditContext(operatorUserId, ip, requestId);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
