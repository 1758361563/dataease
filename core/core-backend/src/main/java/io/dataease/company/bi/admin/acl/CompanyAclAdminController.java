package io.dataease.company.bi.admin.acl;

import io.dataease.company.bi.admin.CompanyAdminAuditContext;
import io.dataease.company.bi.admin.CompanyAdminGuard;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.permission.CompanyCapability;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/company/bi/admin/permissions")
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyAclAdminController {

    private final CompanyAdminGuard adminGuard;
    private final CompanyAclAdminService aclAdminService;

    public CompanyAclAdminController(CompanyAdminGuard adminGuard, CompanyAclAdminService aclAdminService) {
        this.adminGuard = adminGuard;
        this.aclAdminService = aclAdminService;
    }

    @GetMapping("/catalog")
    public CompanyAdminCatalogVO catalog() {
        adminGuard.require(CompanyCapability.ACL_MANAGE);
        return aclAdminService.catalog();
    }

    @GetMapping("/grants")
    public List<CompanyDirectAclVO> grants(@RequestParam("subjectType") String subjectType,
                                           @RequestParam("subjectId") Long subjectId) {
        adminGuard.require(CompanyCapability.ACL_MANAGE);
        return aclAdminService.listDirect(subjectType, subjectId);
    }

    @PostMapping("/grant")
    public CompanyAclGrantVO grant(@RequestBody CompanyAclMutationRequest body, HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.ACL_MANAGE);
        return aclAdminService.grant(body, CompanyAdminAuditContext.from(request, operator));
    }

    @PostMapping("/revoke")
    public CompanyAclGrantVO revoke(@RequestBody CompanyAclMutationRequest body, HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.ACL_MANAGE);
        return aclAdminService.revoke(body, CompanyAdminAuditContext.from(request, operator));
    }
}
