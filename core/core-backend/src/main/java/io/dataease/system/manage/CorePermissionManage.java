package io.dataease.system.manage;

import io.dataease.api.permissions.auth.dto.BusiPerCheckDTO;
import io.dataease.company.bi.permission.CompanyCheckAuthAdapter;
import io.dataease.license.config.XpackInteract;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class CorePermissionManage {

    private final ObjectProvider<CompanyCheckAuthAdapter> companyCheckAuthAdapter;

    public CorePermissionManage(ObjectProvider<CompanyCheckAuthAdapter> companyCheckAuthAdapter) {
        this.companyCheckAuthAdapter = companyCheckAuthAdapter;
    }

    @XpackInteract(value = "corePermissionManage", replace = true)
    public boolean checkAuth(BusiPerCheckDTO dto) {
        CompanyCheckAuthAdapter adapter = companyCheckAuthAdapter.getIfAvailable();
        if (adapter == null) {
            return true;
        }
        return adapter.checkAuth(dto);
    }
}
