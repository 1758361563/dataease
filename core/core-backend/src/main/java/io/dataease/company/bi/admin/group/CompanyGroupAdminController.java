package io.dataease.company.bi.admin.group;

import io.dataease.company.bi.admin.CompanyAdminAuditContext;
import io.dataease.company.bi.admin.CompanyAdminGuard;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.permission.CompanyCapability;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/company/bi/admin/groups")
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyGroupAdminController {

    private final CompanyAdminGuard adminGuard;
    private final CompanyGroupAdminService groupAdminService;

    public CompanyGroupAdminController(CompanyAdminGuard adminGuard, CompanyGroupAdminService groupAdminService) {
        this.adminGuard = adminGuard;
        this.groupAdminService = groupAdminService;
    }

    @GetMapping
    public List<CompanyGroupVO> list() {
        adminGuard.require(CompanyCapability.GROUP_MANAGE);
        return groupAdminService.list();
    }

    @GetMapping("/{id}")
    public CompanyGroupVO detail(@PathVariable("id") Long id) {
        adminGuard.require(CompanyCapability.GROUP_MANAGE);
        return groupAdminService.detail(id);
    }

    @PostMapping
    public CompanyGroupVO create(@RequestBody CompanyGroupUpsertRequest body, HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.GROUP_MANAGE);
        return groupAdminService.create(body, CompanyAdminAuditContext.from(request, operator));
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.POST})
    public CompanyGroupVO update(@PathVariable("id") Long id, @RequestBody CompanyGroupUpsertRequest body,
                                 HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.GROUP_MANAGE);
        return groupAdminService.update(id, body, CompanyAdminAuditContext.from(request, operator));
    }

    @GetMapping("/{id}/members")
    public List<CompanyGroupMemberVO> members(@PathVariable("id") Long id) {
        adminGuard.require(CompanyCapability.GROUP_MANAGE);
        return groupAdminService.listMembers(id);
    }

    @PostMapping("/{id}/members")
    public void addMember(@PathVariable("id") Long id, @RequestBody CompanyGroupMemberRequest body,
                          HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.GROUP_MANAGE);
        groupAdminService.addMember(id, body == null ? null : body.getUserId(),
                CompanyAdminAuditContext.from(request, operator));
    }

    @DeleteMapping("/{id}/members/{userId}")
    public void removeMember(@PathVariable("id") Long id, @PathVariable("userId") Long userId,
                             HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.GROUP_MANAGE);
        groupAdminService.removeMember(id, userId, CompanyAdminAuditContext.from(request, operator));
    }
}
