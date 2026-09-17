package io.dataease.company.bi.feishu;

import io.dataease.company.bi.admin.CompanyAdminAuditContext;
import io.dataease.company.bi.admin.CompanyAdminGuard;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.permission.CompanyCapability;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/company/bi/feishu")
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyFeishuController {

    private final CompanyAdminGuard adminGuard;
    private final CompanyFeishuService feishuService;

    public CompanyFeishuController(CompanyAdminGuard adminGuard, CompanyFeishuService feishuService) {
        this.adminGuard = adminGuard;
        this.feishuService = feishuService;
    }

    @GetMapping("/integration")
    public Map<String, Object> getIntegration(HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        CompanyAdminAuditContext.from(request, operator);
        return feishuService.getIntegration();
    }

    @PutMapping("/integration")
    public Map<String, Object> putIntegration(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        long operator = adminGuard.requireBootstrapAdmin();
        CompanyAdminAuditContext.from(request, operator);
        return feishuService.updateIntegration(body, operator);
    }

    @PostMapping("/test")
    public Map<String, Object> test(HttpServletRequest request) {
        adminGuard.requireBootstrapAdmin();
        return feishuService.testIntegration();
    }

    @GetMapping("/recipients")
    public List<Map<String, Object>> listRecipients(HttpServletRequest request) {
        adminGuard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return feishuService.listRecipients();
    }

    @PostMapping("/recipients")
    public Map<String, Object> createRecipient(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return feishuService.createRecipient(body, operator);
    }

    @RequestMapping(value = "/recipients/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.POST})
    public Map<String, Object> updateRecipient(@PathVariable("id") Long id,
                                               @RequestBody Map<String, Object> body,
                                               HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return feishuService.updateRecipient(id, body, operator);
    }

    @PostMapping("/recipients/{id}/enable")
    public Map<String, Object> enableRecipient(@PathVariable("id") Long id, HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return feishuService.setRecipientStatus(id, "ENABLED", operator);
    }

    @PostMapping("/recipients/{id}/disable")
    public Map<String, Object> disableRecipient(@PathVariable("id") Long id, HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return feishuService.setRecipientStatus(id, "DISABLED", operator);
    }

    @GetMapping("/subscriptions")
    public List<Map<String, Object>> listSubscriptions(HttpServletRequest request) {
        adminGuard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return feishuService.listSubscriptions();
    }

    @GetMapping("/subscriptions/{id}")
    public Map<String, Object> getSubscription(@PathVariable("id") Long id, HttpServletRequest request) {
        adminGuard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return feishuService.getSubscription(id);
    }

    @PostMapping("/subscriptions")
    public Map<String, Object> createSubscription(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return feishuService.createSubscription(body, operator);
    }

    @RequestMapping(value = "/subscriptions/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.POST})
    public Map<String, Object> updateSubscription(@PathVariable("id") Long id,
                                                  @RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return feishuService.updateSubscription(id, body, operator);
    }

    @PostMapping("/subscriptions/{id}/enable")
    public Map<String, Object> enableSubscription(@PathVariable("id") Long id, HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return feishuService.setSubscriptionStatus(id, "ENABLED", operator);
    }

    @PostMapping("/subscriptions/{id}/disable")
    public Map<String, Object> disableSubscription(@PathVariable("id") Long id, HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return feishuService.setSubscriptionStatus(id, "DISABLED", operator);
    }

    @PostMapping("/subscriptions/{id}/run")
    public Map<String, Object> run(@PathVariable("id") Long id, HttpServletRequest request) {
        long operator = adminGuard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return feishuService.runSubscription(id, operator);
    }

    @GetMapping("/subscriptions/{id}/runs")
    public List<Map<String, Object>> runs(@PathVariable("id") Long id, HttpServletRequest request) {
        adminGuard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return feishuService.listRuns(id);
    }

    @GetMapping("/subscriptions/{id}/runs/{runId}")
    public Map<String, Object> runDetail(@PathVariable("id") Long id,
                                         @PathVariable("runId") Long runId,
                                         HttpServletRequest request) {
        adminGuard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE);
        return feishuService.getRun(id, runId);
    }
}
