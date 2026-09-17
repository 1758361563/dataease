package io.dataease.company.bi.feishu;

import io.dataease.auth.bo.TokenUserBO;
import io.dataease.company.bi.admin.CompanyAdminApiFilter;
import io.dataease.company.bi.admin.CompanyAdminGuard;
import io.dataease.company.bi.admin.CompanyAdminTx;
import io.dataease.company.bi.acl.CompanyAclRepository;
import io.dataease.company.bi.group.CompanyGroupRepository;
import io.dataease.company.bi.permission.CompanyCapability;
import io.dataease.company.bi.permission.CompanyDashboardGuard;
import io.dataease.company.bi.permission.DatasetPermissionResolver;
import io.dataease.company.bi.permission.PermissionDecisionService;
import io.dataease.company.bi.user.CompanyAccountRole;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.company.bi.user.CompanyUserStatus;
import io.dataease.exception.DEException;
import io.dataease.utils.AuthUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class CompanyFeishuBffTest {

    private static final long USER_BOOTSTRAP = 1L;
    private static final long USER_ADMIN = 9L;
    private static final long USER_NORMAL = 100L;
    private static final long USER_READONLY = 200L;
    private static final Long DASH_OK = 1292912367851671552L;
    private static final Long DASH_DENY = 11L;

    private MemoryStore store;
    private CompanyFeishuService service;
    private CompanyAdminGuard guard;
    private PermissionDecisionService decision;
    private FakeGateway gateway;
    private AtomicLong ids;

    @BeforeEach
    void setUp() {
        FakeUsers users = new FakeUsers();
        users.put(user(USER_BOOTSTRAP, CompanyAccountRole.ADMIN));
        users.put(user(USER_ADMIN, CompanyAccountRole.ADMIN));
        users.put(user(USER_NORMAL, CompanyAccountRole.NORMAL));
        users.put(user(USER_READONLY, CompanyAccountRole.READONLY));
        decision = new PermissionDecisionService(users,
                new DatasetPermissionResolver(mock(CompanyAclRepository.class), mock(CompanyGroupRepository.class)));
        guard = new CompanyAdminGuard(decision);
        store = new MemoryStore();
        gateway = new FakeGateway();
        ids = new AtomicLong(5000);
        CompanyFeishuProperties properties = new CompanyFeishuProperties();
        properties.setInternalToken("test-token");
        properties.setDryRun(true);
        CompanyDashboardGuard dashGuard = mock(CompanyDashboardGuard.class);
        doAnswer(invocation -> {
            Long dashboardId = invocation.getArgument(0);
            if (!DASH_OK.equals(dashboardId)) {
                CompanyDashboardGuard.deny();
            }
            return null;
        }).when(dashGuard).requireView(any());
        service = new CompanyFeishuService(store, dashGuard, new ImmediateTx(), properties, gateway, ids::incrementAndGet);
        seedApp();
    }

    @AfterEach
    void tearDown() {
        AuthUtils.remove();
    }

    @Test
    void filterRequiresFeishuCapability() {
        assertEquals(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE,
                CompanyAdminApiFilter.requiredCapability("/company/bi/feishu/recipients", null));
        assertEquals(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE,
                CompanyAdminApiFilter.requiredCapability("/de2api/company/bi/feishu/subscriptions/1/run",
                        "/de2api/company/bi/feishu/subscriptions/1/run"));
        assertTrue(decision.hasCapability(USER_BOOTSTRAP, CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE));
        assertTrue(decision.hasCapability(USER_ADMIN, CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE));
        assertFalse(decision.hasCapability(USER_NORMAL, CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE));
        assertFalse(decision.hasCapability(USER_READONLY, CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE));
    }

    @Test
    void bootstrapAdminCanWriteIntegrationOtherAdminCannot() {
        AuthUtils.setUser(new TokenUserBO(USER_BOOTSTRAP, 1L));
        Map<String, Object> updated = service.updateIntegration(Map.of(
                "appId", "cli_test_app",
                "appSecretRef", "FEISHU_APP_SECRET",
                "status", "ENABLED"
        ), USER_BOOTSTRAP);
        assertEquals("cli_test_app", updated.get("appId"));
        assertEquals(true, updated.get("hasSecretRef"));
        assertFalse(updated.toString().toLowerCase().contains("kw2q"));

        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        assertThrows(DEException.class, () -> guard.requireBootstrapAdmin());
        AuthUtils.setUser(new TokenUserBO(USER_BOOTSTRAP, 1L));
        assertEquals(USER_BOOTSTRAP, guard.requireBootstrapAdmin());
    }

    @Test
    void rejectsPlaintextSecret() {
        AuthUtils.setUser(new TokenUserBO(USER_BOOTSTRAP, 1L));
        DEException ex = assertThrows(DEException.class, () -> service.updateIntegration(Map.of(
                "appId", "cli_test_app",
                "appSecretRef", "FEISHU_APP_SECRET",
                "appSecret", "should-not-store"
        ), USER_BOOTSTRAP));
        assertTrue(ex.getMessage().contains("密钥"));
    }

    @Test
    void normalAndReadonlyDeniedByGuard() {
        AuthUtils.setUser(new TokenUserBO(USER_NORMAL, 1L));
        assertThrows(DEException.class, () -> guard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE));
        AuthUtils.setUser(new TokenUserBO(USER_READONLY, 1L));
        assertThrows(DEException.class, () -> guard.require(CompanyCapability.FEISHU_SUBSCRIPTION_MANAGE));
    }

    @Test
    void createSubscriptionBindsRecipientsAndBlocksUnknownDashboard() {
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        Map<String, Object> person = service.createRecipient(recipient("PERSONAL", "user_id", "user_alpha"), USER_ADMIN);
        Map<String, Object> chat = service.createRecipient(recipient("CHAT", "chat_id", "oc_test_chat"), USER_ADMIN);
        Map<String, Object> created = service.createSubscription(subscription(String.valueOf(DASH_OK),
                List.of(person.get("id"), chat.get("id"))), USER_ADMIN);
        assertEquals(2, ((List<?>) created.get("recipientIds")).size());
        assertEquals("DRAFT", created.get("status"));

        assertThrows(DEException.class, () -> service.createSubscription(
                subscription(String.valueOf(DASH_DENY), List.of(person.get("id"))), USER_ADMIN));
        assertThrows(DEException.class, () -> service.createRecipient(
                recipient("PERSONAL", "chat_id", "mismatch"), USER_ADMIN));
        assertThrows(DEException.class, () -> service.createSubscription(
                subscription(String.valueOf(DASH_OK), List.of("1")), USER_ADMIN));
        Map<String, Object> badFilter = subscription(String.valueOf(DASH_OK), List.of(person.get("id"), chat.get("id")));
        badFilter.put("fixedFilterJson", "not-json");
        assertThrows(DEException.class, () -> service.createSubscription(badFilter, USER_ADMIN));
        assertEquals(1, store.listSubscriptions().size());
    }

    @Test
    void missingSubscriptionRejected() {
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        assertThrows(DEException.class, () -> service.getSubscription(404L));
        assertThrows(DEException.class, () -> service.runSubscription(404L, USER_ADMIN));
    }

    @Test
    void manualRunCallsGatewayAndReturnsRunId() {
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        Map<String, Object> person = service.createRecipient(recipient("PERSONAL", "user_id", "user_beta"), USER_ADMIN);
        Map<String, Object> chat = service.createRecipient(recipient("CHAT", "chat_id", "oc_beta"), USER_ADMIN);
        Map<String, Object> created = service.createSubscription(subscription(String.valueOf(DASH_OK),
                List.of(person.get("id"), chat.get("id"))), USER_ADMIN);
        service.setSubscriptionStatus(Long.parseLong((String) created.get("id")), "ENABLED", USER_ADMIN);
        Map<String, Object> accepted = service.runSubscription(Long.parseLong((String) created.get("id")), USER_ADMIN);
        assertEquals("7500000000000000099", accepted.get("runId"));
        assertEquals(true, accepted.get("accepted"));
        assertEquals(1, gateway.calls.size());
        assertEquals(created.get("id"), String.valueOf(gateway.calls.get(0).subscriptionId()));
        assertTrue(gateway.calls.get(0).dryRun());

        store.runs.add(run(7500000000000000099L, Long.parseLong((String) created.get("id"))));
        store.targets.add(target(7500000000000000099L, Long.parseLong((String) person.get("id")), "user_id", "SUCCEEDED", "om_dry_1"));
        store.targets.add(target(7500000000000000099L, Long.parseLong((String) chat.get("id")), "chat_id", "SUCCEEDED", "om_dry_2"));
        Map<String, Object> detail = service.getRun(Long.parseLong((String) created.get("id")), 7500000000000000099L);
        List<?> targets = (List<?>) detail.get("targets");
        assertEquals(2, targets.size());
        assertEquals("om_dry_1", ((Map<?, ?>) targets.get(0)).get("messageId"));
    }

    @Test
    void adminCannotBypassDashboardAclOnRun() {
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        Map<String, Object> person = service.createRecipient(recipient("PERSONAL", "user_id", "user_gamma"), USER_ADMIN);
        Map<String, Object> created = service.createSubscription(subscription(String.valueOf(DASH_OK),
                List.of(person.get("id"))), USER_ADMIN);
        CompanyFeishuRepository.Subscription row = store.findSubscription(Long.parseLong((String) created.get("id")));
        row.dashboardId = String.valueOf(DASH_DENY);
        row.status = "ENABLED";
        store.updateSubscription(row);
        assertThrows(DEException.class, () -> service.runSubscription(row.id, USER_ADMIN));
        assertTrue(gateway.calls.isEmpty());
    }

    private void seedApp() {
        CompanyFeishuRepository.AppConfig app = new CompanyFeishuRepository.AppConfig();
        app.id = 1L;
        app.appId = "cli_seed";
        app.appSecretRef = "FEISHU_APP_SECRET";
        app.status = "ENABLED";
        store.insertAppConfig(app);
    }

    private static Map<String, Object> recipient(String type, String receiveIdType, String feishuId) {
        Map<String, Object> body = new HashMap<>();
        body.put("recipientType", type);
        body.put("receiveIdType", receiveIdType);
        body.put("displayName", type + "-" + feishuId);
        body.put("feishuId", feishuId);
        return body;
    }

    private static Map<String, Object> subscription(String dashboardId, List<Object> recipientIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "[BFF-TEST] card");
        body.put("dashboardId", dashboardId);
        body.put("chartId", "7501169547546726400");
        body.put("recipientIds", recipientIds);
        body.put("fixedFilterJson", Map.of("version", 1, "items", List.of()));
        return body;
    }

    private static CompanyFeishuRepository.DeliveryRun run(long id, long subscriptionId) {
        CompanyFeishuRepository.DeliveryRun row = new CompanyFeishuRepository.DeliveryRun();
        row.id = id;
        row.subscriptionId = subscriptionId;
        row.triggerType = "MANUAL";
        row.status = "SUCCEEDED";
        row.screenshotStatus = "SKIPPED";
        row.uploadStatus = "SKIPPED";
        row.createdAt = 1L;
        return row;
    }

    private static CompanyFeishuRepository.DeliveryTarget target(long runId, long recipientId, String type,
                                                                 String status, String messageId) {
        CompanyFeishuRepository.DeliveryTarget row = new CompanyFeishuRepository.DeliveryTarget();
        row.id = runId + recipientId;
        row.runId = runId;
        row.recipientId = recipientId;
        row.receiveIdType = type;
        row.status = status;
        row.feishuMessageId = messageId;
        row.retryCount = 0;
        return row;
    }

    private static CompanyBiUser user(long id, CompanyAccountRole role) {
        CompanyBiUser user = new CompanyBiUser();
        user.setId(id);
        user.setUsername("u" + id);
        user.setAccountRole(role.name());
        user.setStatus(CompanyUserStatus.ENABLED.name());
        return user;
    }

    private static final class ImmediateTx implements CompanyAdminTx {
        @Override
        public <T> T execute(java.util.function.Supplier<T> work) {
            return work.get();
        }
    }

    private static final class FakeGateway implements CompanyFeishuDeliveryGateway {
        private final List<Call> calls = new ArrayList<>();

        @Override
        public Map<String, Object> start(long subscriptionId, long operatorId, boolean dryRun) {
            calls.add(new Call(subscriptionId, operatorId, dryRun));
            return Map.of("runId", "7500000000000000099", "status", "PENDING", "accepted", true);
        }

        private record Call(long subscriptionId, long operatorId, boolean dryRun) {
        }
    }

    private static final class FakeUsers extends CompanyBiUserService {
        private final Map<Long, CompanyBiUser> byId = new HashMap<>();

        private FakeUsers() {
            super(null);
        }

        void put(CompanyBiUser user) {
            byId.put(user.getId(), user);
        }

        @Override
        public CompanyBiUser findById(Long id) {
            return byId.get(id);
        }
    }

    private static final class MemoryStore implements CompanyFeishuRepository {
        private AppConfig app;
        private final List<Recipient> recipients = new ArrayList<>();
        private final List<IdentityMap> maps = new ArrayList<>();
        private final List<Subscription> subscriptions = new ArrayList<>();
        private final List<long[]> links = new ArrayList<>();
        private final List<DeliveryRun> runs = new ArrayList<>();
        private final List<DeliveryTarget> targets = new ArrayList<>();

        @Override
        public AppConfig findAppConfig() {
            return app;
        }

        @Override
        public AppConfig findAppConfigByAppId(String appId) {
            return app != null && app.appId.equals(appId) ? app : null;
        }

        @Override
        public void insertAppConfig(AppConfig row) {
            app = row;
        }

        @Override
        public void updateAppConfig(AppConfig row) {
            app = row;
        }

        @Override
        public List<Recipient> listRecipients() {
            return recipients;
        }

        @Override
        public Recipient findRecipient(Long id) {
            return recipients.stream().filter(item -> item.id.equals(id)).findFirst().orElse(null);
        }

        @Override
        public Recipient findRecipientByKey(String appId, String receiveIdType, String feishuId) {
            return recipients.stream()
                    .filter(item -> item.appId.equals(appId) && item.receiveIdType.equals(receiveIdType)
                            && item.feishuId.equals(feishuId))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public void insertRecipient(Recipient row) {
            recipients.add(row);
        }

        @Override
        public void updateRecipient(Recipient row) {
            recipients.replaceAll(item -> item.id.equals(row.id) ? row : item);
        }

        @Override
        public IdentityMap findMapByRecipient(Long recipientId) {
            return maps.stream().filter(item -> item.recipientId.equals(recipientId)).findFirst().orElse(null);
        }

        @Override
        public IdentityMap findMapByUser(String appId, Long biUserId) {
            return maps.stream().filter(item -> item.appId.equals(appId) && item.biUserId.equals(biUserId))
                    .findFirst().orElse(null);
        }

        @Override
        public void insertIdentityMap(IdentityMap row) {
            maps.add(row);
        }

        @Override
        public List<Subscription> listSubscriptions() {
            return subscriptions.stream().filter(item -> !"DELETED".equals(item.status)).toList();
        }

        @Override
        public Subscription findSubscription(Long id) {
            return subscriptions.stream().filter(item -> item.id.equals(id)).findFirst().orElse(null);
        }

        @Override
        public void insertSubscription(Subscription row) {
            subscriptions.add(row);
        }

        @Override
        public void updateSubscription(Subscription row) {
            subscriptions.replaceAll(item -> item.id.equals(row.id) ? row : item);
        }

        @Override
        public List<Long> listRecipientIds(Long subscriptionId) {
            List<Long> ids = new ArrayList<>();
            for (long[] link : links) {
                if (link[0] == subscriptionId) {
                    ids.add(link[1]);
                }
            }
            return ids;
        }

        @Override
        public void deleteSubscriptionRecipients(Long subscriptionId) {
            links.removeIf(link -> link[0] == subscriptionId);
        }

        @Override
        public void insertSubscriptionRecipient(Long id, Long subscriptionId, Long recipientId, long createdAt, Long createdBy) {
            links.add(new long[]{subscriptionId, recipientId});
        }

        @Override
        public List<DeliveryRun> listRuns(Long subscriptionId, int limit) {
            return runs.stream().filter(item -> item.subscriptionId.equals(subscriptionId)).limit(limit).toList();
        }

        @Override
        public DeliveryRun findRun(Long id) {
            return runs.stream().filter(item -> item.id.equals(id)).findFirst().orElse(null);
        }

        @Override
        public List<DeliveryTarget> listTargets(Long runId) {
            return targets.stream().filter(item -> item.runId.equals(runId)).toList();
        }
    }
}
