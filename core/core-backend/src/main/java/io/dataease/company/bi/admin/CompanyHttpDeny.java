package io.dataease.company.bi.admin;

import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import io.dataease.utils.LogUtil;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public final class CompanyHttpDeny {

    private CompanyHttpDeny() {
    }

    public static void writeForbidden(HttpServletResponse response, DEException e) throws IOException {
        LogUtil.error(e.getMessage(), e);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        int code = e.getCode() == 0 ? ResultCode.PERMISSION_NO_ACCESS.code() : e.getCode();
        String msg = e.getMessage() == null ? ResultCode.PERMISSION_NO_ACCESS.message() : e.getMessage();
        response.getWriter().write("{\"code\":" + code + ",\"msg\":\"" + jsonEscape(msg) + "\",\"data\":null}");
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
