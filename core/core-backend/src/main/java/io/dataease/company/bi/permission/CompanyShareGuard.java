package io.dataease.company.bi.permission;

import io.dataease.api.system.vo.ShareBaseVO;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyShareGuard {

    public ShareBaseVO disabledShareBase() {
        ShareBaseVO vo = new ShareBaseVO();
        vo.setDisable(true);
        vo.setPeRequire(false);
        return vo;
    }

    public void denyMutations() {
        CompanyDashboardGuard.deny();
    }
}
