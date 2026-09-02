package io.dataease.company.bi.permission;

import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.user.CompanyAccountRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyExportPolicy {

    private final int normalExt;
    private final int readonlyExt;

    public CompanyExportPolicy(
            @Value("${company.bi.export.normal:0}") int normalExt,
            @Value("${company.bi.export.readonly:0}") int readonlyExt) {
        this.normalExt = normalize(normalExt);
        this.readonlyExt = normalize(readonlyExt);
    }

    public int extFor(CompanyAccountRole role) {
        if (role == CompanyAccountRole.NORMAL) {
            return normalExt;
        }
        if (role == CompanyAccountRole.READONLY) {
            return readonlyExt;
        }
        return 0;
    }

    public boolean hasExportCapability(CompanyAccountRole role) {
        if (role == CompanyAccountRole.ADMIN) {
            return true;
        }
        if (role == CompanyAccountRole.NORMAL) {
            return normalExt != 0;
        }
        return false;
    }

    public boolean allows(CompanyAccountRole role, CompanyExportKind kind) {
        if (role == CompanyAccountRole.ADMIN) {
            return true;
        }
        if (role == CompanyAccountRole.READONLY) {
            return false;
        }
        if (role != CompanyAccountRole.NORMAL || kind == null) {
            return false;
        }
        return digitOn(normalExt, kind);
    }

    static boolean digitOn(int ext, CompanyExportKind kind) {
        return switch (kind) {
            case IMAGE_PDF_TEMPLATE -> ext % 10 != 0;
            case EXCEL_VIEW -> (ext / 10) % 10 != 0;
            case DETAIL -> (ext / 100) % 10 != 0;
        };
    }

    private static int normalize(int ext) {
        return Math.max(ext, 0);
    }
}
