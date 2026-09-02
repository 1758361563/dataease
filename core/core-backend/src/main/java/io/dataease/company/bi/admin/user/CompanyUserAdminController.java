package io.dataease.company.bi.admin.user;

import io.dataease.company.bi.admin.CompanyAdminAuditContext;
import io.dataease.company.bi.admin.CompanyAdminGuard;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.permission.CompanyCapability;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/company/bi/admin/users")
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyUserAdminController {

    private final CompanyAdminGuard adminGuard;
    private final CompanyUserAdminService userAdminService;

    public CompanyUserAdminController(CompanyAdminGuard adminGuard, CompanyUserAdminService userAdminService) {
        this.adminGuard = adminGuard;
        this.userAdminService = userAdminService;
    }

    @GetMapping
    public List<CompanyUserVO> list(HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.USER_MANAGE);
        CompanyAdminAuditContext.from(request, operator);
        return userAdminService.list();
    }

    @GetMapping("/{id}")
    public CompanyUserVO detail(@PathVariable("id") Long id, HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.USER_MANAGE);
        CompanyAdminAuditContext.from(request, operator);
        return userAdminService.detail(id);
    }

    @PostMapping
    public CompanyUserVO create(@RequestBody CompanyUserCreateRequest body, HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.USER_MANAGE);
        return userAdminService.create(body, CompanyAdminAuditContext.from(request, operator));
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.POST})
    public CompanyUserVO update(@PathVariable("id") Long id, @RequestBody CompanyUserUpdateRequest body,
                                HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.USER_MANAGE);
        return userAdminService.update(id, body, CompanyAdminAuditContext.from(request, operator));
    }

    @RequestMapping(value = "/{id}/role", method = {RequestMethod.PUT, RequestMethod.POST})
    public CompanyUserVO changeRole(@PathVariable("id") Long id, @RequestBody CompanyRoleChangeRequest body,
                                    HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.USER_MANAGE);
        return userAdminService.changeRole(id, body == null ? null : body.getAccountRole(),
                CompanyAdminAuditContext.from(request, operator));
    }

    @RequestMapping(value = "/{id}/enable", method = {RequestMethod.PUT, RequestMethod.POST})
    public CompanyUserVO enable(@PathVariable("id") Long id, HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.USER_MANAGE);
        return userAdminService.enable(id, CompanyAdminAuditContext.from(request, operator));
    }

    @RequestMapping(value = "/{id}/disable", method = {RequestMethod.PUT, RequestMethod.POST})
    public CompanyUserVO disable(@PathVariable("id") Long id, HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.USER_MANAGE);
        return userAdminService.disable(id, CompanyAdminAuditContext.from(request, operator));
    }

    @PostMapping("/{id}/resetPassword")
    public void resetPassword(@PathVariable("id") Long id, @RequestBody CompanyPasswordResetRequest body,
                              HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.USER_MANAGE);
        userAdminService.resetPassword(id, body == null ? null : body.getPassword(),
                CompanyAdminAuditContext.from(request, operator));
    }
}
