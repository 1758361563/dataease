package io.dataease.company.bi.me;

import io.dataease.auth.bo.TokenUserBO;
import io.dataease.company.bi.admin.CompanyAdminErrors;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.auth.CompanyUserStatusGuard;
import io.dataease.company.bi.permission.CompanyCapability;
import io.dataease.company.bi.permission.PermissionDecisionService;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.utils.AuthUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/company/bi/me")
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyMeController {

    private final CompanyUserStatusGuard statusGuard;
    private final PermissionDecisionService permissionDecisionService;

    public CompanyMeController(CompanyUserStatusGuard statusGuard,
                               PermissionDecisionService permissionDecisionService) {
        this.statusGuard = statusGuard;
        this.permissionDecisionService = permissionDecisionService;
    }

    @GetMapping
    public CompanyMeVO me() {
        TokenUserBO tokenUser = AuthUtils.getUser();
        if (tokenUser == null || tokenUser.getUserId() == null) {
            CompanyAdminErrors.deny();
        }
        CompanyBiUser user = statusGuard.requireActive(tokenUser.getUserId());
        CompanyMeVO vo = new CompanyMeVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setDisplayName(StringUtils.isNotBlank(user.getDisplayName()) ? user.getDisplayName() : user.getUsername());
        vo.setAccountRole(user.getAccountRole());
        List<String> capabilities = new ArrayList<>();
        for (CompanyCapability capability : permissionDecisionService.listCapabilities(user.getId())) {
            capabilities.add(capability.name());
        }
        vo.setCapabilities(capabilities);
        return vo;
    }
}
