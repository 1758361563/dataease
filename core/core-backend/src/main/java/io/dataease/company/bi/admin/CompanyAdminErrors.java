package io.dataease.company.bi.admin;

import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;

public final class CompanyAdminErrors {

    private CompanyAdminErrors() {
    }

    public static void deny() {
        DEException.throwException(ResultCode.PERMISSION_NO_ACCESS.code(), ResultCode.PERMISSION_NO_ACCESS.message());
    }

    public static void invalid(String message) {
        DEException.throwException(ResultCode.PARAM_IS_INVALID.code(), message);
    }

    public static void conflict(String message) {
        DEException.throwException(ResultCode.PARAM_IS_INVALID.code(), message);
    }
}
