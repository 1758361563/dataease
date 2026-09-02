package io.dataease.company.bi.permission;

import io.dataease.auth.bo.TokenUserBO;
import io.dataease.company.bi.acl.CompanyAclRepository;
import io.dataease.company.bi.acl.CompanyBiResourceAcl;
import io.dataease.company.bi.acl.CompanyRelationLevel;
import io.dataease.company.bi.acl.CompanyResourceType;
import io.dataease.company.bi.acl.CompanySubjectType;
import io.dataease.company.bi.admin.CompanyAdminApiFilter;
import io.dataease.company.bi.admin.CompanyAdminAuditContext;
import io.dataease.company.bi.admin.CompanyAdminErrors;
import io.dataease.company.bi.admin.CompanyAdminGuard;
import io.dataease.company.bi.admin.CompanyAdminTx;
import io.dataease.company.bi.admin.acl.CompanyAclAdminService;
import io.dataease.company.bi.admin.acl.CompanyAclMutationRequest;
import io.dataease.company.bi.admin.acl.CompanyAclResourceValidator;
import io.dataease.company.bi.admin.acl.CompanyAdminCatalogService;
import io.dataease.company.bi.admin.acl.CompanyAdminCatalogVO;
import io.dataease.company.bi.admin.acl.CompanyCatalogNodeVO;
import io.dataease.company.bi.admin.acl.CompanyDirectAclVO;
import io.dataease.company.bi.admin.group.CompanyGroupAdminService;
import io.dataease.company.bi.admin.group.CompanyGroupUpsertRequest;
import io.dataease.company.bi.admin.user.CompanyPasswordResetRequest;
import io.dataease.company.bi.admin.user.CompanyUserAdminService;
import io.dataease.company.bi.admin.user.CompanyUserCreateRequest;
import io.dataease.company.bi.admin.user.CompanyUserVO;
import io.dataease.company.bi.audit.CompanyAuditActionType;
import io.dataease.company.bi.audit.CompanyAuditLogService;
import io.dataease.company.bi.audit.CompanyAuditRecorder;
import io.dataease.company.bi.auth.CompanyPasswordHasher;
import io.dataease.company.bi.auth.CompanyUserStatusGuard;
import io.dataease.company.bi.group.CompanyBiGroup;
import io.dataease.company.bi.group.CompanyBiGroupMember;
import io.dataease.company.bi.group.CompanyGroupRepository;
import io.dataease.company.bi.user.CompanyAccountRole;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.company.bi.user.CompanyUserStatus;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import io.dataease.utils.AuthUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyPermissionP4AdminTest {

    private static final long USER_ADMIN = 1L;
    private static final long USER_NORMAL = 100L;
    private static final long USER_READONLY = 200L;
    private static final long DASH_X = 8201L;
    private static final long DASH_Y = 8202L;
    private static final long FOLDER_A = 8100L;
    private static final long DATASET_X = 9301L;
    private static final long DATASET_FOLDER = 9300L;

    private FakeUserService users;
    private FakeGroupRepository groups;
    private FakeAclRepository acl;
    private FakeHierarchySource hierarchy;
    private MemoryAudit audit;
    private ImmediateTx tx;
    private PermissionDecisionService decision;
    private CompanyAdminGuard adminGuard;
    private CompanyUserAdminService userAdmin;
    private CompanyGroupAdminService groupAdmin;
    private CompanyAclAdminService aclAdmin;
    private CompanyUserStatusGuard statusGuard;
    private CompanyPasswordHasher hasher;
    private AtomicLong ids;
    private FakeValidator validator;
    private FakeCatalog catalog;

    @BeforeEach
    void setUp() {
        users = new FakeUserService();
        groups = new FakeGroupRepository();
        acl = new FakeAclRepository();
        hierarchy = new FakeHierarchySource();
        hierarchy.folder(FOLDER_A, 0L);
        hierarchy.dash(DASH_X, FOLDER_A);
        hierarchy.dash(DASH_Y, FOLDER_A);
        audit = new MemoryAudit();
        tx = new ImmediateTx();
        ids = new AtomicLong(10000);
        hasher = new CompanyPasswordHasher();
        DatasetPermissionResolver datasetResolver = new DatasetPermissionResolver(acl, groups);
        DashboardPermissionResolver dashboardResolver = new DashboardPermissionResolver(acl, groups, hierarchy);
        CompanyExportPolicy exportPolicy = new CompanyExportPolicy(0, 0);
        decision = new PermissionDecisionService(users, datasetResolver, dashboardResolver,
                new DashboardPermissionProjectionService(exportPolicy), exportPolicy);
        adminGuard = new CompanyAdminGuard(decision);
        userAdmin = new CompanyUserAdminService(users, groups, hasher, audit, tx, ids::incrementAndGet);
        groupAdmin = new CompanyGroupAdminService(groups, users, audit, tx);
        validator = new FakeValidator();
        validator.allow(CompanyResourceType.DASHBOARD, DASH_X);
        validator.allow(CompanyResourceType.DASHBOARD, DASH_Y);
        validator.allow(CompanyResourceType.DASHBOARD_FOLDER, FOLDER_A);
        validator.allow(CompanyResourceType.DATASET, DATASET_X);
        catalog = new FakeCatalog();
        CompanyCatalogNodeVO y = new CompanyCatalogNodeVO();
        y.setId(DASH_Y);
        y.setName("Hidden Y");
        y.setType("DASHBOARD");
        y.setLeaf(true);
        y.setPid(FOLDER_A);
        catalog.dashboards.add(y);
        aclAdmin = new CompanyAclAdminService(acl, users, groups, validator, catalog, audit, tx);
        statusGuard = new CompanyUserStatusGuard(users);
        users.put(enabled(USER_ADMIN, CompanyAccountRole.ADMIN));
        users.put(enabled(USER_NORMAL, CompanyAccountRole.NORMAL));
        users.put(enabled(USER_READONLY, CompanyAccountRole.READONLY));
    }

    @AfterEach
    void tearDown() {
        AuthUtils.remove();
    }

    @Test
    void p4T1NormalUserAdminDenied() {
        AuthUtils.setUser(new TokenUserBO(USER_NORMAL, 1L));
        DEException ex = assertThrows(DEException.class, () -> adminGuard.require(CompanyCapability.USER_MANAGE));
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
        assertEquals(CompanyCapability.USER_MANAGE, CompanyAdminApiFilter.requiredCapability("/company/bi/admin/users", null));
    }

    @Test
    void p4T2ReadonlyAclAdminDenied() {
        AuthUtils.setUser(new TokenUserBO(USER_READONLY, 1L));
        DEException ex = assertThrows(DEException.class, () -> adminGuard.require(CompanyCapability.ACL_MANAGE));
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
        assertEquals(CompanyCapability.ACL_MANAGE, CompanyAdminApiFilter.requiredCapability("/de2api/company/bi/admin/permissions/catalog", "/de2api/company/bi/admin/permissions/catalog"));
    }

    @Test
    void p4T3AdminCreateUserBcrypt() {
        CompanyUserCreateRequest req = new CompanyUserCreateRequest();
        req.setUsername("p4_user");
        req.setDisplayName("P4");
        req.setAccountRole("NORMAL");
        req.setStatus("ENABLED");
        req.setPassword("Temp#123");
        CompanyUserVO vo = userAdmin.create(req, ctx());
        assertNotNull(vo.getId());
        assertEquals("p4_user", vo.getUsername());
        for (Field field : vo.getClass().getDeclaredFields()) {
            assertFalse(field.getName().toLowerCase().contains("password"));
            assertFalse(field.getName().toLowerCase().contains("hash"));
        }
        CompanyBiUser stored = users.findById(vo.getId());
        assertTrue(hasher.matches("Temp#123", stored.getPasswordHash()));
        assertFalse(stored.getPasswordHash().equals("Temp#123"));
        assertTrue(audit.actions.contains(CompanyAuditActionType.USER_CREATE));
    }

    @Test
    void p4T4DuplicateUsernameDenied() {
        CompanyUserCreateRequest req = new CompanyUserCreateRequest();
        req.setUsername("dup");
        req.setAccountRole("NORMAL");
        req.setPassword("Temp#123");
        userAdmin.create(req, ctx());
        DEException ex = assertThrows(DEException.class, () -> userAdmin.create(req, ctx()));
        assertEquals(ResultCode.PARAM_IS_INVALID.code(), ex.getCode());
    }

    @Test
    void p4T5DisableUserStatusGuardFails() {
        userAdmin.disable(USER_NORMAL, ctx());
        DEException ex = assertThrows(DEException.class, () -> statusGuard.requireActive(USER_NORMAL));
        assertEquals(401, ex.getCode());
        assertFalse(decision.hasCapability(USER_NORMAL, CompanyCapability.DASHBOARD_VIEW));
    }

    @Test
    void p4T6BootstrapDisableDenied() {
        DEException ex = assertThrows(DEException.class, () -> userAdmin.disable(USER_ADMIN, ctx()));
        assertEquals(ResultCode.PARAM_IS_INVALID.code(), ex.getCode());
        assertTrue(users.findById(USER_ADMIN).isEnabled());
    }

    @Test
    void p4T7BootstrapRoleDowngradeDenied() {
        DEException ex = assertThrows(DEException.class, () -> userAdmin.changeRole(USER_ADMIN, "NORMAL", ctx()));
        assertEquals(ResultCode.PARAM_IS_INVALID.code(), ex.getCode());
        assertEquals("ADMIN", users.findById(USER_ADMIN).getAccountRole());
    }

    @Test
    void p4T8RoleChangeImmediateCapability() {
        assertTrue(decision.hasCapability(USER_ADMIN, CompanyCapability.USER_MANAGE));
        users.put(enabled(900L, CompanyAccountRole.ADMIN));
        userAdmin.changeRole(900L, "NORMAL", ctx());
        assertFalse(decision.hasCapability(900L, CompanyCapability.USER_MANAGE));
        assertTrue(decision.hasCapability(900L, CompanyCapability.DASHBOARD_VIEW));
        assertTrue(audit.actions.contains(CompanyAuditActionType.USER_ROLE_CHANGE));
    }

    @Test
    void p4T9CreateGroup() {
        CompanyGroupUpsertRequest req = new CompanyGroupUpsertRequest();
        req.setName("p4-g");
        req.setDescription("d");
        var vo = groupAdmin.create(req, ctx());
        assertNotNull(vo.getId());
        assertEquals("ENABLED", vo.getStatus());
        assertTrue(audit.actions.contains(CompanyAuditActionType.GROUP_CREATE));
    }

    @Test
    void p4T10AddMember() {
        Long groupId = groupAdmin.create(named("g-add"), ctx()).getId();
        groupAdmin.addMember(groupId, USER_NORMAL, ctx());
        groupAdmin.addMember(groupId, USER_NORMAL, ctx());
        assertEquals(1, groups.listMembers(groupId).size());
        assertTrue(audit.actions.contains(CompanyAuditActionType.GROUP_MEMBER_ADD));
    }

    @Test
    void p4T11RemoveMemberInheritedPermissionLost() {
        Long groupId = groupAdmin.create(named("g-inherit"), ctx()).getId();
        groupAdmin.addMember(groupId, USER_NORMAL, ctx());
        grantFolder(CompanySubjectType.GROUP, groupId, FOLDER_A);
        assertTrue(decision.hasDashboardAccess(USER_NORMAL, DASH_X));
        groupAdmin.removeMember(groupId, USER_NORMAL, ctx());
        assertFalse(decision.hasDashboardAccess(USER_NORMAL, DASH_X));
        assertTrue(audit.actions.contains(CompanyAuditActionType.GROUP_MEMBER_REMOVE));
    }

    @Test
    void p4T12DisabledGroupAclNoEffect() {
        Long groupId = groupAdmin.create(named("g-off"), ctx()).getId();
        groupAdmin.addMember(groupId, USER_NORMAL, ctx());
        grantFolder(CompanySubjectType.GROUP, groupId, FOLDER_A);
        assertTrue(decision.hasDashboardAccess(USER_NORMAL, DASH_X));
        CompanyGroupUpsertRequest req = new CompanyGroupUpsertRequest();
        req.setStatus("DISABLED");
        groupAdmin.update(groupId, req, ctx());
        assertFalse(decision.hasDashboardAccess(USER_NORMAL, DASH_X));
        assertTrue(audit.actions.contains(CompanyAuditActionType.GROUP_STATUS_CHANGE));
    }

    @Test
    void p4T13GrantDashboardVisitor() {
        var vo = aclAdmin.grant(aclReq(CompanySubjectType.USER, USER_NORMAL, CompanyResourceType.DASHBOARD, DASH_X, "VISITOR"), ctx());
        assertEquals("GRANTED", vo.getResult());
        assertTrue(decision.hasDashboardAccess(USER_NORMAL, DASH_X));
        assertEquals("VISITOR", decision.resolveDashboardRelation(USER_NORMAL, DASH_X).name());
    }

    @Test
    void p4T14ChangeVisitorToOwner() {
        aclAdmin.grant(aclReq(CompanySubjectType.USER, USER_NORMAL, CompanyResourceType.DASHBOARD, DASH_X, "VISITOR"), ctx());
        var vo = aclAdmin.grant(aclReq(CompanySubjectType.USER, USER_NORMAL, CompanyResourceType.DASHBOARD, DASH_X, "OWNER"), ctx());
        assertEquals("CHANGED", vo.getResult());
        assertEquals("OWNER", decision.resolveDashboardRelation(USER_NORMAL, DASH_X).name());
        assertTrue(audit.actions.contains(CompanyAuditActionType.ACL_CHANGE));
    }

    @Test
    void p4T15RevokeDashboard() {
        aclAdmin.grant(aclReq(CompanySubjectType.USER, USER_NORMAL, CompanyResourceType.DASHBOARD, DASH_X, "VISITOR"), ctx());
        var vo = aclAdmin.revoke(aclReq(CompanySubjectType.USER, USER_NORMAL, CompanyResourceType.DASHBOARD, DASH_X, null), ctx());
        assertEquals("REVOKED", vo.getResult());
        assertFalse(decision.hasDashboardAccess(USER_NORMAL, DASH_X));
        var again = aclAdmin.revoke(aclReq(CompanySubjectType.USER, USER_NORMAL, CompanyResourceType.DASHBOARD, DASH_X, null), ctx());
        assertEquals("UNCHANGED", again.getResult());
    }

    @Test
    void p4T16GrantFolderVisitorInheritedDashboard() {
        aclAdmin.grant(aclReq(CompanySubjectType.USER, USER_NORMAL, CompanyResourceType.DASHBOARD_FOLDER, FOLDER_A, "VISITOR"), ctx());
        assertTrue(decision.hasDashboardAccess(USER_NORMAL, DASH_X));
        List<CompanyDirectAclVO> direct = aclAdmin.listDirect("USER", USER_NORMAL);
        assertEquals(1, direct.size());
        assertEquals("DASHBOARD_FOLDER", direct.get(0).getResourceType());
        assertFalse(direct.stream().anyMatch(row -> DASH_X == row.getResourceId()));
    }

    @Test
    void p4T17GrantDatasetRead() {
        aclAdmin.grant(aclReq(CompanySubjectType.USER, USER_NORMAL, CompanyResourceType.DATASET, DATASET_X, "READ"), ctx());
        assertTrue(decision.hasDatasetRead(USER_NORMAL, DATASET_X));
        assertEquals("READ", aclAdmin.listDirect("USER", USER_NORMAL).get(0).getRelation());
    }

    @Test
    void p4T18DatasetOwnerRejected() {
        DEException ex = assertThrows(DEException.class, () -> aclAdmin.grant(
                aclReq(CompanySubjectType.USER, USER_NORMAL, CompanyResourceType.DATASET, DATASET_X, "OWNER"), ctx()));
        assertEquals(ResultCode.PARAM_IS_INVALID.code(), ex.getCode());
        assertFalse(decision.hasDatasetRead(USER_NORMAL, DATASET_X));
    }

    @Test
    void p4T19InvalidSubjectDenied() {
        DEException ex = assertThrows(DEException.class, () -> aclAdmin.grant(
                aclReq(CompanySubjectType.USER, 999999L, CompanyResourceType.DASHBOARD, DASH_X, "VISITOR"), ctx()));
        assertEquals(ResultCode.PARAM_IS_INVALID.code(), ex.getCode());
    }

    @Test
    void p4T20InvalidResourceTypeMismatchDenied() {
        DEException folderAsDash = assertThrows(DEException.class, () -> aclAdmin.grant(
                aclReq(CompanySubjectType.USER, USER_NORMAL, CompanyResourceType.DASHBOARD, FOLDER_A, "VISITOR"), ctx()));
        assertEquals(ResultCode.PARAM_IS_INVALID.code(), folderAsDash.getCode());
        DEException dashAsFolder = assertThrows(DEException.class, () -> aclAdmin.grant(
                aclReq(CompanySubjectType.USER, USER_NORMAL, CompanyResourceType.DASHBOARD_FOLDER, DASH_X, "VISITOR"), ctx()));
        assertEquals(ResultCode.PARAM_IS_INVALID.code(), dashAsFolder.getCode());
        DEException unknown = assertThrows(DEException.class, () -> aclAdmin.grant(
                aclReq(CompanySubjectType.USER, USER_NORMAL, CompanyResourceType.DASHBOARD, 1L, "VISITOR"), ctx()));
        assertEquals(ResultCode.PARAM_IS_INVALID.code(), unknown.getCode());
        DEException datasetFolder = assertThrows(DEException.class, () -> {
            validator.allow(CompanyResourceType.DATASET, DATASET_FOLDER);
            validator.deny(CompanyResourceType.DATASET, DATASET_FOLDER);
            aclAdmin.grant(aclReq(CompanySubjectType.USER, USER_NORMAL, CompanyResourceType.DATASET, DATASET_FOLDER, "READ"), ctx());
        });
        assertEquals(ResultCode.PARAM_IS_INVALID.code(), datasetFolder.getCode());
    }

    @Test
    void p4T21AdminCatalogSeesResourceWithoutOwnAcl() {
        assertFalse(decision.hasDashboardAccess(USER_ADMIN, DASH_Y));
        CompanyAdminCatalogVO vo = aclAdmin.catalog();
        assertTrue(vo.getDashboards().stream().anyMatch(n -> DASH_Y == n.getId()));
    }

    @Test
    void p4T22RuntimeTreeHidesSameResource() {
        assertFalse(decision.accessibleDashboardIds(USER_ADMIN).contains(DASH_Y));
        assertTrue(aclAdmin.catalog().getDashboards().stream().anyMatch(n -> DASH_Y == n.getId()));
    }

    @Test
    void p4T23AdminSelfGrantRuntimeImmediateAllow() {
        assertFalse(decision.hasDashboardAccess(USER_ADMIN, DASH_Y));
        aclAdmin.grant(aclReq(CompanySubjectType.USER, USER_ADMIN, CompanyResourceType.DASHBOARD, DASH_Y, "VISITOR"), ctx());
        assertTrue(decision.hasDashboardAccess(USER_ADMIN, DASH_Y));
        assertTrue(decision.canEditDashboard(USER_ADMIN, DASH_Y));
        assertTrue(audit.actions.contains(CompanyAuditActionType.ACL_GRANT));
    }

    @Test
    void p4T24SelfRevokeImmediateDeny() {
        aclAdmin.grant(aclReq(CompanySubjectType.USER, USER_ADMIN, CompanyResourceType.DASHBOARD, DASH_Y, "VISITOR"), ctx());
        aclAdmin.revoke(aclReq(CompanySubjectType.USER, USER_ADMIN, CompanyResourceType.DASHBOARD, DASH_Y, null), ctx());
        assertFalse(decision.hasDashboardAccess(USER_ADMIN, DASH_Y));
        assertTrue(audit.actions.contains(CompanyAuditActionType.ACL_REVOKE));
    }

    @Test
    void p4T25AuditAclGrant() {
        aclAdmin.grant(aclReq(CompanySubjectType.USER, USER_NORMAL, CompanyResourceType.DASHBOARD, DASH_X, "VISITOR"), ctx());
        assertEquals(1, audit.count(CompanyAuditActionType.ACL_GRANT));
        assertTrue(audit.lastJson().contains("VISITOR"));
        assertFalse(audit.lastJson().toLowerCase().contains("password"));
    }

    @Test
    void p4T26AuditRoleChange() {
        users.put(enabled(901L, CompanyAccountRole.NORMAL));
        userAdmin.changeRole(901L, "ADMIN", ctx());
        assertEquals(1, audit.count(CompanyAuditActionType.USER_ROLE_CHANGE));
    }

    @Test
    void p4T27PasswordResetAuditContainsNoSecret() {
        CompanyPasswordResetRequest ignored = new CompanyPasswordResetRequest();
        ignored.setPassword("New#456");
        userAdmin.resetPassword(USER_NORMAL, "New#456", ctx());
        assertTrue(audit.actions.contains(CompanyAuditActionType.PASSWORD_RESET));
        String json = String.join(" ", audit.jsons);
        assertFalse(json.contains("New#456"));
        assertFalse(json.toLowerCase().contains("password_hash"));
        assertFalse(json.toLowerCase().contains("passwordhash"));
        assertTrue(json.contains("passwordChanged"));
        assertTrue(hasher.matches("New#456", users.findById(USER_NORMAL).getPasswordHash()));
    }

    @Test
    void p4T28AuditFailureRollsBackBusinessChange() {
        SnapshotTx snap = new SnapshotTx(users);
        MemoryAudit failing = new MemoryAudit();
        failing.fail = true;
        CompanyUserAdminService svc = new CompanyUserAdminService(users, groups, hasher, failing, snap, ids::incrementAndGet);
        int before = users.byId.size();
        CompanyUserCreateRequest req = new CompanyUserCreateRequest();
        req.setUsername("rollback_user");
        req.setAccountRole("NORMAL");
        req.setPassword("Temp#123");
        assertThrows(IllegalStateException.class, () -> svc.create(req, ctx()));
        assertEquals(before, users.byId.size());
        assertTrue(users.findByUsername("rollback_user") == null);
    }

    @Test
    void p4T29NormalDatasourceBackendDenied() {
        CompanyCapabilityPathFilter filter = new CompanyCapabilityPathFilter(decision,
                CompanyCapability.DATASOURCE_ACCESS, List.of("/datasource", "/engine"));
        assertTrue(filter.matches("/datasource/tree", "/de2api/datasource/tree"));
        assertTrue(filter.matches("/engine/getEngine", "/de2api/engine/getEngine"));
        assertFalse(filter.matches("/chartData/getData", "/de2api/chartData/getData"));
        AuthUtils.setUser(new TokenUserBO(USER_NORMAL, 1L));
        DEException ex = assertThrows(DEException.class, () -> {
            if (!decision.hasCapability(USER_NORMAL, CompanyCapability.DATASOURCE_ACCESS)) {
                CompanyAdminErrors.deny();
            }
        });
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p4T30NormalDatasetDesignerCrudDenied() {
        assertTrue(CompanyDatasetDesignFilter.matches("/datasetTree/create"));
        assertTrue(CompanyDatasetDesignFilter.matches("/datasetTree/tree"));
        assertTrue(CompanyDatasetDesignFilter.matches("/datasetTree/details/1"));
        AuthUtils.setUser(new TokenUserBO(USER_NORMAL, 1L));
        DEException ex = assertThrows(DEException.class, () -> new CompanyDatasetDesignGuard(decision).requireDatasetDesign());
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p4T31NormalRuntimeChartFilterStillWorksWithRead() {
        aclAdmin.grant(aclReq(CompanySubjectType.USER, USER_NORMAL, CompanyResourceType.DATASET, DATASET_X, "READ"), ctx());
        assertTrue(decision.hasDatasetRead(USER_NORMAL, DATASET_X));
        assertFalse(CompanyDatasetDesignFilter.isDesignEndpoint("/datasetData/enumValue", "/de2api/datasetData/enumValue"));
        assertFalse(CompanyDatasetDesignFilter.isDesignEndpoint("/chartData/getData", "/de2api/chartData/getData"));
        assertFalse(CompanyDatasetDesignFilter.isDesignEndpoint("/datasetTree/getSqlParams", "/de2api/datasetTree/getSqlParams"));
    }

    @Test
    void p4T32NormalEtlBackendDeniedIfPresent() {
        CompanyCapabilityPathFilter filter = new CompanyCapabilityPathFilter(decision,
                CompanyCapability.ETL_ACCESS, List.of("/sync"));
        assertTrue(filter.matches("/sync/task", "/de2api/sync/task"));
        AuthUtils.setUser(new TokenUserBO(USER_NORMAL, 1L));
        assertFalse(decision.hasCapability(USER_NORMAL, CompanyCapability.ETL_ACCESS));
    }

    private CompanyAdminAuditContext ctx() {
        return new CompanyAdminAuditContext(USER_ADMIN, "127.0.0.1", "req-1");
    }

    private static CompanyGroupUpsertRequest named(String name) {
        CompanyGroupUpsertRequest req = new CompanyGroupUpsertRequest();
        req.setName(name);
        return req;
    }

    private static CompanyAclMutationRequest aclReq(CompanySubjectType subjectType, Long subjectId,
                                                    CompanyResourceType resourceType, Long resourceId, String relation) {
        CompanyAclMutationRequest req = new CompanyAclMutationRequest();
        req.setSubjectType(subjectType.name());
        req.setSubjectId(subjectId);
        req.setResourceType(resourceType.name());
        req.setResourceId(resourceId);
        req.setRelation(relation);
        return req;
    }

    private void grantFolder(CompanySubjectType subjectType, Long subjectId, Long folderId) {
        acl.upsert(subjectType, subjectId, CompanyResourceType.DASHBOARD_FOLDER, folderId, CompanyRelationLevel.VISITOR, USER_ADMIN);
    }

    private static CompanyBiUser enabled(long id, CompanyAccountRole role) {
        CompanyBiUser user = new CompanyBiUser();
        user.setId(id);
        user.setUsername("u" + id);
        user.setDisplayName("u" + id);
        user.setPasswordHash("x");
        user.setAccountRole(role.name());
        user.setStatus(CompanyUserStatus.ENABLED.name());
        user.setCreatedAt(1L);
        user.setUpdatedAt(1L);
        return user;
    }

    static final class ImmediateTx implements CompanyAdminTx {
        @Override
        public <T> T execute(Supplier<T> work) {
            return work.get();
        }
    }

    static final class SnapshotTx implements CompanyAdminTx {
        private final FakeUserService users;

        SnapshotTx(FakeUserService users) {
            this.users = users;
        }

        @Override
        public <T> T execute(Supplier<T> work) {
            Map<Long, CompanyBiUser> snapshot = new HashMap<>(users.byId);
            try {
                return work.get();
            } catch (RuntimeException e) {
                users.byId.clear();
                users.byId.putAll(snapshot);
                throw e;
            }
        }
    }

    static final class MemoryAudit implements CompanyAuditRecorder {
        final List<CompanyAuditActionType> actions = new ArrayList<>();
        final List<String> jsons = new ArrayList<>();
        boolean fail;

        @Override
        public void record(CompanyAdminAuditContext context, CompanyAuditActionType actionType, String targetType,
                           Long targetId, String beforeJson, String afterJson) {
            if (fail) {
                throw new IllegalStateException("audit fail");
            }
            CompanyAuditLogService.assertNoSecrets(beforeJson);
            CompanyAuditLogService.assertNoSecrets(afterJson);
            actions.add(actionType);
            if (beforeJson != null) {
                jsons.add(beforeJson);
            }
            if (afterJson != null) {
                jsons.add(afterJson);
            }
        }

        int count(CompanyAuditActionType type) {
            int n = 0;
            for (CompanyAuditActionType action : actions) {
                if (action == type) {
                    n++;
                }
            }
            return n;
        }

        String lastJson() {
            return jsons.isEmpty() ? "" : jsons.get(jsons.size() - 1);
        }
    }

    static final class FakeValidator extends CompanyAclResourceValidator {
        private final Set<String> allowed = new HashSet<>();

        FakeValidator() {
            super(null, null);
        }

        void allow(CompanyResourceType type, Long id) {
            allowed.add(type + ":" + id);
        }

        void deny(CompanyResourceType type, Long id) {
            allowed.remove(type + ":" + id);
        }

        @Override
        public void requireValid(CompanyResourceType resourceType, Long resourceId) {
            if (!allowed.contains(resourceType + ":" + resourceId)) {
                CompanyAdminErrors.invalid("资源无效");
            }
        }
    }

    static final class FakeCatalog extends CompanyAdminCatalogService {
        final List<CompanyCatalogNodeVO> dashboards = new ArrayList<>();
        final List<CompanyCatalogNodeVO> datasets = new ArrayList<>();
        final Map<String, String> nameMap = new HashMap<>();

        FakeCatalog() {
            super(null, null);
        }

        @Override
        public CompanyAdminCatalogVO catalog() {
            CompanyAdminCatalogVO vo = new CompanyAdminCatalogVO();
            vo.setDashboards(dashboards);
            vo.setDatasets(datasets);
            return vo;
        }

        @Override
        public Map<Long, String> names(CompanyResourceType resourceType, List<Long> ids) {
            Map<Long, String> result = new HashMap<>();
            if (ids == null) {
                return result;
            }
            for (Long id : ids) {
                result.put(id, nameMap.getOrDefault(resourceType + ":" + id, "n" + id));
            }
            return result;
        }
    }

    static final class FakeUserService extends CompanyBiUserService {
        final Map<Long, CompanyBiUser> byId = new HashMap<>();

        FakeUserService() {
            super(null);
        }

        void put(CompanyBiUser user) {
            byId.put(user.getId(), user);
        }

        @Override
        public CompanyBiUser findById(Long id) {
            return id == null ? null : byId.get(id);
        }

        @Override
        public CompanyBiUser findByUsername(String username) {
            if (username == null) {
                return null;
            }
            for (CompanyBiUser user : byId.values()) {
                if (username.equals(user.getUsername())) {
                    return user;
                }
            }
            return null;
        }

        @Override
        public void insert(CompanyBiUser user) {
            byId.put(user.getId(), user);
        }

        @Override
        public void updateById(CompanyBiUser user) {
            byId.put(user.getId(), user);
        }

        @Override
        public List<CompanyBiUser> listAll() {
            return new ArrayList<>(byId.values());
        }

        @Override
        public List<CompanyBiUser> listByIds(Collection<Long> ids) {
            List<CompanyBiUser> result = new ArrayList<>();
            if (ids == null) {
                return result;
            }
            for (Long id : ids) {
                CompanyBiUser user = byId.get(id);
                if (user != null) {
                    result.add(user);
                }
            }
            return result;
        }

        @Override
        public boolean usernameTaken(String username, Long excludeUserId) {
            CompanyBiUser existing = findByUsername(username);
            return existing != null && (excludeUserId == null || !excludeUserId.equals(existing.getId()));
        }
    }

    static final class FakeGroupRepository extends CompanyGroupRepository {
        private final Map<Long, CompanyBiGroup> groups = new HashMap<>();
        private final List<CompanyBiGroupMember> members = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong(500);

        FakeGroupRepository() {
            super(null, null);
        }

        @Override
        public List<Long> listEnabledGroupIds(Long userId) {
            List<Long> ids = new ArrayList<>();
            for (CompanyBiGroupMember member : members) {
                if (!member.getUserId().equals(userId)) {
                    continue;
                }
                CompanyBiGroup group = groups.get(member.getGroupId());
                if (group != null && CompanyUserStatus.ENABLED.name().equals(group.getStatus())) {
                    ids.add(group.getId());
                }
            }
            return ids;
        }

        @Override
        public void insertGroup(CompanyBiGroup group) {
            if (group.getId() == null) {
                group.setId(seq.incrementAndGet());
            }
            groups.put(group.getId(), group);
        }

        @Override
        public void insertMember(CompanyBiGroupMember member) {
            if (existsMember(member.getUserId(), member.getGroupId())) {
                return;
            }
            members.add(member);
        }

        @Override
        public void deleteMember(Long userId, Long groupId) {
            members.removeIf(m -> m.getUserId().equals(userId) && m.getGroupId().equals(groupId));
        }

        @Override
        public CompanyBiGroup findById(Long id) {
            return id == null ? null : groups.get(id);
        }

        @Override
        public CompanyBiGroup findByName(String name) {
            for (CompanyBiGroup group : groups.values()) {
                if (group.getName().equals(name)) {
                    return group;
                }
            }
            return null;
        }

        @Override
        public List<CompanyBiGroup> listAll() {
            return new ArrayList<>(groups.values());
        }

        @Override
        public void updateById(CompanyBiGroup group) {
            groups.put(group.getId(), group);
        }

        @Override
        public boolean existsMember(Long userId, Long groupId) {
            return members.stream().anyMatch(m -> m.getUserId().equals(userId) && m.getGroupId().equals(groupId));
        }

        @Override
        public List<CompanyBiGroupMember> listMembers(Long groupId) {
            List<CompanyBiGroupMember> result = new ArrayList<>();
            for (CompanyBiGroupMember member : members) {
                if (member.getGroupId().equals(groupId)) {
                    result.add(member);
                }
            }
            return result;
        }

        @Override
        public List<CompanyBiGroupMember> listMemberships(Long userId) {
            List<CompanyBiGroupMember> result = new ArrayList<>();
            for (CompanyBiGroupMember member : members) {
                if (member.getUserId().equals(userId)) {
                    result.add(member);
                }
            }
            return result;
        }
    }

    static final class FakeAclRepository extends CompanyAclRepository {
        private final Map<String, CompanyBiResourceAcl> rows = new HashMap<>();
        private final AtomicLong seq = new AtomicLong(1);

        FakeAclRepository() {
            super(null);
        }

        @Override
        public boolean existsDatasetAcl(CompanySubjectType subjectType, Long subjectId, Long datasetId) {
            return find(subjectType, subjectId, CompanyResourceType.DATASET, datasetId) != null;
        }

        @Override
        public boolean existsDatasetAclForGroups(Collection<Long> groupIds, Long datasetId) {
            if (groupIds == null) {
                return false;
            }
            for (Long groupId : groupIds) {
                if (existsDatasetAcl(CompanySubjectType.GROUP, groupId, datasetId)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean existsDashboardAcl(CompanySubjectType subjectType, Long subjectId, Long dashboardId) {
            return find(subjectType, subjectId, CompanyResourceType.DASHBOARD, dashboardId) != null;
        }

        @Override
        public boolean existsFolderAcl(CompanySubjectType subjectType, Long subjectId, Long folderId) {
            return find(subjectType, subjectId, CompanyResourceType.DASHBOARD_FOLDER, folderId) != null;
        }

        @Override
        public List<CompanyBiResourceAcl> listDashboardAcls(Long userId, Collection<Long> groupIds) {
            return listResource(CompanyResourceType.DASHBOARD, userId, groupIds);
        }

        @Override
        public List<CompanyBiResourceAcl> listFolderAcls(Long userId, Collection<Long> groupIds) {
            return listResource(CompanyResourceType.DASHBOARD_FOLDER, userId, groupIds);
        }

        private List<CompanyBiResourceAcl> listResource(CompanyResourceType type, Long userId, Collection<Long> groupIds) {
            List<CompanyBiResourceAcl> result = new ArrayList<>();
            for (CompanyBiResourceAcl row : rows.values()) {
                if (!type.name().equals(row.getResourceType())) {
                    continue;
                }
                if (CompanySubjectType.USER.name().equals(row.getSubjectType()) && userId.equals(row.getSubjectId())) {
                    result.add(row);
                } else if (CompanySubjectType.GROUP.name().equals(row.getSubjectType())
                        && groupIds != null && groupIds.contains(row.getSubjectId())) {
                    result.add(row);
                }
            }
            return result;
        }

        @Override
        public CompanyBiResourceAcl find(CompanySubjectType subjectType, Long subjectId,
                                         CompanyResourceType resourceType, Long resourceId) {
            return rows.get(key(subjectType, subjectId, resourceType, resourceId));
        }

        @Override
        public List<CompanyBiResourceAcl> listDirectBySubject(CompanySubjectType subjectType, Long subjectId) {
            List<CompanyBiResourceAcl> result = new ArrayList<>();
            for (CompanyBiResourceAcl row : rows.values()) {
                if (subjectType.name().equals(row.getSubjectType()) && subjectId.equals(row.getSubjectId())) {
                    result.add(row);
                }
            }
            return result;
        }

        @Override
        public AclWriteResult upsert(CompanySubjectType subjectType, Long subjectId, CompanyResourceType resourceType,
                                     Long resourceId, CompanyRelationLevel relationLevel, Long operatorUserId) {
            String k = key(subjectType, subjectId, resourceType, resourceId);
            CompanyBiResourceAcl existing = rows.get(k);
            if (existing == null) {
                CompanyBiResourceAcl row = new CompanyBiResourceAcl();
                row.setId(seq.incrementAndGet());
                row.setSubjectType(subjectType.name());
                row.setSubjectId(subjectId);
                row.setResourceType(resourceType.name());
                row.setResourceId(resourceId);
                row.setRelationLevel(relationLevel.name());
                rows.put(k, row);
                return AclWriteResult.GRANTED;
            }
            if (relationLevel.name().equals(existing.getRelationLevel())) {
                return AclWriteResult.UNCHANGED;
            }
            existing.setRelationLevel(relationLevel.name());
            return AclWriteResult.CHANGED;
        }

        @Override
        public AclWriteResult revoke(CompanySubjectType subjectType, Long subjectId, CompanyResourceType resourceType,
                                     Long resourceId) {
            String k = key(subjectType, subjectId, resourceType, resourceId);
            if (rows.remove(k) == null) {
                return AclWriteResult.UNCHANGED;
            }
            return AclWriteResult.REVOKED;
        }

        private static String key(CompanySubjectType subjectType, Long subjectId, CompanyResourceType resourceType,
                                  Long resourceId) {
            return subjectType + ":" + subjectId + ":" + resourceType + ":" + resourceId;
        }
    }

    static final class FakeHierarchySource implements VisualizationHierarchySource {
        private final List<VisualizationHierarchy.Node> nodes = new ArrayList<>();

        void folder(long id, long pid) {
            nodes.add(new VisualizationHierarchy.Node(id, pid, true, false));
        }

        void dash(long id, long pid) {
            nodes.add(new VisualizationHierarchy.Node(id, pid, false, true));
        }

        @Override
        public VisualizationHierarchy load() {
            return new VisualizationHierarchy(nodes);
        }
    }
}
