package io.dataease.company.bi.permission;

import io.dataease.api.chart.request.ChartExcelRequest;
import io.dataease.api.system.vo.ShareBaseVO;
import io.dataease.auth.bo.TokenUserBO;
import io.dataease.chart.dao.auto.entity.CoreChartView;
import io.dataease.company.bi.acl.CompanyAclRepository;
import io.dataease.company.bi.acl.CompanyBiResourceAcl;
import io.dataease.company.bi.acl.CompanyRelationLevel;
import io.dataease.company.bi.acl.CompanyResourceType;
import io.dataease.company.bi.acl.CompanySubjectType;
import io.dataease.company.bi.group.CompanyGroupRepository;
import io.dataease.company.bi.user.CompanyAccountRole;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.company.bi.user.CompanyUserStatus;
import io.dataease.dataset.dao.auto.entity.CoreDatasetTableField;
import io.dataease.exception.DEException;
import io.dataease.model.BusiNodeVO;
import io.dataease.result.ResultCode;
import io.dataease.utils.AuthUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyPermissionP2Test {

    private static final long USER_U = 100L;
    private static final long USER_ADMIN = 1L;
    private static final long USER_READONLY = 200L;
    private static final long GROUP_G = 501L;
    private static final long DASHBOARD_X = 7001L;
    private static final long DASHBOARD_SIB = 7002L;
    private static final long FOLDER_A = 7100L;
    private static final long CHART_V = 22L;
    private static final long FIELD_F = 11L;

    private FakeUserService users;
    private FakeAclRepository acl;
    private FakeGroupRepository groups;
    private PermissionDecisionService decision;
    private CompanyDashboardGuard guard;
    private CompanyExportPolicy exportPolicy;
    private DashboardPermissionProjectionService projectionService;

    @BeforeEach
    void setUp() {
        users = new FakeUserService();
        acl = new FakeAclRepository();
        groups = new FakeGroupRepository();
        DatasetPermissionResolver datasetResolver = new DatasetPermissionResolver(acl, groups);
        DashboardPermissionResolver dashboardResolver = new DashboardPermissionResolver(acl, groups);
        exportPolicy = new CompanyExportPolicy(0, 0);
        projectionService = new DashboardPermissionProjectionService(exportPolicy);
        decision = new PermissionDecisionService(users, datasetResolver, dashboardResolver, projectionService, exportPolicy);
        guard = new CompanyDashboardGuard(decision);
        users.put(enabled(USER_U, CompanyAccountRole.NORMAL));
        users.put(enabled(USER_ADMIN, CompanyAccountRole.ADMIN));
        users.put(enabled(USER_READONLY, CompanyAccountRole.READONLY));
    }

    @AfterEach
    void tearDown() {
        AuthUtils.remove();
    }

    @Test
    void p2T1UserDirectVisitorAccessTrue() {
        acl.grantDashboard(CompanySubjectType.USER, USER_U, DASHBOARD_X, CompanyRelationLevel.VISITOR);
        assertTrue(decision.hasDashboardAccess(USER_U, DASHBOARD_X));
    }

    @Test
    void p2T2NoAclFalse() {
        assertFalse(decision.hasDashboardAccess(USER_U, DASHBOARD_X));
    }

    @Test
    void p2T3GroupDirectVisitorTrue() {
        groups.addMember(USER_U, GROUP_G, true);
        acl.grantDashboard(CompanySubjectType.GROUP, GROUP_G, DASHBOARD_X, CompanyRelationLevel.VISITOR);
        assertTrue(decision.hasDashboardAccess(USER_U, DASHBOARD_X));
    }

    @Test
    void p2T4DisabledUserFalseEvenWithAcl() {
        acl.grantDashboard(CompanySubjectType.USER, USER_U, DASHBOARD_X, CompanyRelationLevel.VISITOR);
        users.byId.get(USER_U).setStatus(CompanyUserStatus.DISABLED.name());
        assertFalse(decision.hasDashboardAccess(USER_U, DASHBOARD_X));
    }

    @Test
    void p2T5AdminVisitorViewAndEdit() {
        acl.grantDashboard(CompanySubjectType.USER, USER_ADMIN, DASHBOARD_X, CompanyRelationLevel.VISITOR);
        assertTrue(decision.canViewDashboard(USER_ADMIN, DASHBOARD_X));
        assertTrue(decision.canEditDashboard(USER_ADMIN, DASHBOARD_X));
    }

    @Test
    void p2T6NormalVisitorViewNoEdit() {
        acl.grantDashboard(CompanySubjectType.USER, USER_U, DASHBOARD_X, CompanyRelationLevel.VISITOR);
        assertTrue(decision.canViewDashboard(USER_U, DASHBOARD_X));
        assertFalse(decision.canEditDashboard(USER_U, DASHBOARD_X));
    }

    @Test
    void p2T7NormalOwnerStillNoEdit() {
        acl.grantDashboard(CompanySubjectType.USER, USER_U, DASHBOARD_X, CompanyRelationLevel.OWNER);
        assertTrue(decision.canViewDashboard(USER_U, DASHBOARD_X));
        assertFalse(decision.canEditDashboard(USER_U, DASHBOARD_X));
    }

    @Test
    void p2T8TreeKeepsAuthorizedLeafAndAncestorHidesSibling() {
        acl.grantDashboard(CompanySubjectType.USER, USER_U, DASHBOARD_X, CompanyRelationLevel.VISITOR);
        BusiNodeVO root = node(0L, "root", false, null);
        BusiNodeVO folder = node(FOLDER_A, "folder-a", false, null);
        folder.setChildren(List.of(
                node(DASHBOARD_X, "dash-x", true, null),
                node(DASHBOARD_SIB, "dash-sib", true, null)
        ));
        root.setChildren(List.of(folder, node(8000L, "empty-folder", false, List.of())));
        DashboardPermissionProjection leaf = projectionService.project(CompanyAccountRole.NORMAL, true);
        List<BusiNodeVO> pruned = CompanyDashboardTreePruner.prune(
                List.of(root), decision.accessibleDashboardIds(USER_U), leaf, projectionService.folderShell());
        assertEquals(1, pruned.size());
        assertEquals(0L, pruned.get(0).getId());
        assertEquals(1, pruned.get(0).getChildren().size());
        BusiNodeVO keptFolder = pruned.get(0).getChildren().get(0);
        assertEquals(FOLDER_A, keptFolder.getId());
        assertEquals(1, keptFolder.getWeight());
        assertEquals(1, keptFolder.getChildren().size());
        assertEquals(DASHBOARD_X, keptFolder.getChildren().get(0).getId());
        assertEquals(6, keptFolder.getChildren().get(0).getWeight());
    }

    @Test
    void p2T9FindByIdNoAclDeny() {
        AuthUtils.setUser(new TokenUserBO(USER_U, 1L));
        DEException ex = assertThrows(DEException.class, () -> guard.requireView(DASHBOARD_X));
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p2T10FindByIdAdminVisitorWeight9() {
        acl.grantDashboard(CompanySubjectType.USER, USER_ADMIN, DASHBOARD_X, CompanyRelationLevel.VISITOR);
        DashboardPermissionProjection projection = decision.projectDashboard(USER_ADMIN, DASHBOARD_X);
        assertEquals(9, projection.weight());
    }

    @Test
    void p2T11FindByIdNormalWeight6AndConfiguredExt() {
        acl.grantDashboard(CompanySubjectType.USER, USER_U, DASHBOARD_X, CompanyRelationLevel.VISITOR);
        DashboardPermissionProjection projection = decision.projectDashboard(USER_U, DASHBOARD_X);
        assertEquals(6, projection.weight());
        assertEquals(0, projection.ext());
    }

    @Test
    void p2T12FindByIdReadonlyWeight1() {
        acl.grantDashboard(CompanySubjectType.USER, USER_READONLY, DASHBOARD_X, CompanyRelationLevel.VISITOR);
        DashboardPermissionProjection projection = decision.projectDashboard(USER_READONLY, DASHBOARD_X);
        assertEquals(1, projection.weight());
    }

    @Test
    void p2T13EditNormalDeny() {
        acl.grantDashboard(CompanySubjectType.USER, USER_U, DASHBOARD_X, CompanyRelationLevel.VISITOR);
        AuthUtils.setUser(new TokenUserBO(USER_U, 1L));
        DEException ex = assertThrows(DEException.class, () -> guard.requireEdit(DASHBOARD_X));
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p2T14EditAdminVisitorAllow() {
        acl.grantDashboard(CompanySubjectType.USER, USER_ADMIN, DASHBOARD_X, CompanyRelationLevel.VISITOR);
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        guard.requireEdit(DASHBOARD_X);
        assertTrue(decision.canEditDashboard(USER_ADMIN, DASHBOARD_X));
    }

    @Test
    void p2T15ExportNoDashboardAclDeny() {
        AuthUtils.setUser(new TokenUserBO(USER_U, 1L));
        assertFalse(decision.canExportDashboard(USER_U, DASHBOARD_X, CompanyExportKind.EXCEL_VIEW));
        DEException ex = assertThrows(DEException.class, () -> guard.requireExport(DASHBOARD_X, CompanyExportKind.EXCEL_VIEW));
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p2T16AsyncExportDeniedBeforeAddTask() {
        AuthUtils.setUser(new TokenUserBO(USER_U, 1L));
        ChartExcelRequest request = new ChartExcelRequest();
        request.setViewId("1");
        request.setDvId(String.valueOf(DASHBOARD_X));
        assertFalse(decision.canExportDashboard(USER_U, DASHBOARD_X, CompanyExportKind.EXCEL_VIEW));
        DEException ex = assertThrows(DEException.class, () -> guard.requireExport(DASHBOARD_X, CompanyExportKind.EXCEL_VIEW));
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p2T17RecentStoreDropsUnauthorized() {
        acl.grantDashboard(CompanySubjectType.USER, USER_U, DASHBOARD_X, CompanyRelationLevel.VISITOR);
        Set<Long> accessible = decision.accessibleDashboardIds(USER_U);
        List<Long> recent = List.of(DASHBOARD_X, DASHBOARD_SIB);
        List<Long> visible = recent.stream().filter(accessible::contains).toList();
        assertEquals(List.of(DASHBOARD_X), visible);
        assertFalse(accessible.contains(DASHBOARD_SIB));
    }

    @Test
    void p2T18ShareDisabled() {
        CompanyShareGuard shareGuard = new CompanyShareGuard();
        ShareBaseVO vo = shareGuard.disabledShareBase();
        assertTrue(vo.isDisable());
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        DEException ex = assertThrows(DEException.class, shareGuard::denyMutations);
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p2T19AdminCreateGrantsCreatorVisitor() {
        long created = 80001L;
        assertTrue(acl.insertIfAbsentUserDashboardVisitor(USER_ADMIN, created, USER_ADMIN));
        assertTrue(decision.hasDashboardAccess(USER_ADMIN, created));
        assertEquals(CompanyResourceRelation.VISITOR, decision.resolveDashboardRelation(USER_ADMIN, created));
        assertFalse(acl.insertIfAbsentUserDashboardVisitor(USER_ADMIN, created, USER_ADMIN));
    }

    @Test
    void p2DisabledGroupDoesNotGrantDashboard() {
        groups.addMember(USER_U, GROUP_G, false);
        acl.grantDashboard(CompanySubjectType.GROUP, GROUP_G, DASHBOARD_X, CompanyRelationLevel.VISITOR);
        assertFalse(decision.hasDashboardAccess(USER_U, DASHBOARD_X));
    }

    @Test
    void p2DatasetReadUnchangedWithoutDashboardAcl() {
        assertFalse(decision.hasDatasetRead(USER_U, 9001L));
        acl.grant(CompanySubjectType.USER, USER_U, 9001L);
        assertTrue(decision.hasDatasetRead(USER_U, 9001L));
        assertFalse(decision.hasDashboardAccess(USER_U, DASHBOARD_X));
    }

    @Test
    void p2C1DeleteFieldNoDashboardAclDeny() {
        CompanyDashboardExportGuard exportGuard = chartFieldGuard();
        AuthUtils.setUser(new TokenUserBO(USER_U, 1L));
        DEException ex = assertThrows(DEException.class, () -> exportGuard.requireSavedChartFieldEdit(FIELD_F));
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p2C2DeleteFieldNormalVisitorDeny() {
        acl.grantDashboard(CompanySubjectType.USER, USER_U, DASHBOARD_X, CompanyRelationLevel.VISITOR);
        CompanyDashboardExportGuard exportGuard = chartFieldGuard();
        AuthUtils.setUser(new TokenUserBO(USER_U, 1L));
        DEException ex = assertThrows(DEException.class, () -> exportGuard.requireSavedChartFieldEdit(FIELD_F));
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p2C3DeleteFieldNormalOwnerDeny() {
        acl.grantDashboard(CompanySubjectType.USER, USER_U, DASHBOARD_X, CompanyRelationLevel.OWNER);
        CompanyDashboardExportGuard exportGuard = chartFieldGuard();
        AuthUtils.setUser(new TokenUserBO(USER_U, 1L));
        DEException ex = assertThrows(DEException.class, () -> exportGuard.requireSavedChartFieldEdit(FIELD_F));
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p2C4DeleteFieldAdminVisitorAllow() {
        acl.grantDashboard(CompanySubjectType.USER, USER_ADMIN, DASHBOARD_X, CompanyRelationLevel.VISITOR);
        CompanyDashboardExportGuard exportGuard = chartFieldGuard();
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        exportGuard.requireSavedChartFieldEdit(FIELD_F);
        assertTrue(decision.canEditDashboard(USER_ADMIN, DASHBOARD_X));
    }

    @Test
    void p2CDeleteFieldWithoutChartIdDeny() {
        CoreDatasetTableField field = new CoreDatasetTableField();
        field.setId(FIELD_F);
        CompanyDashboardExportGuard exportGuard = new StubExportGuard(guard, Map.of(FIELD_F, field), Map.of());
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        DEException ex = assertThrows(DEException.class, () -> exportGuard.requireSavedChartFieldEdit(FIELD_F));
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    private CompanyDashboardExportGuard chartFieldGuard() {
        CoreDatasetTableField field = new CoreDatasetTableField();
        field.setId(FIELD_F);
        field.setChartId(CHART_V);
        CoreChartView chart = new CoreChartView();
        chart.setId(CHART_V);
        chart.setSceneId(DASHBOARD_X);
        return new StubExportGuard(guard, Map.of(FIELD_F, field), Map.of(CHART_V, chart));
    }

    static final class StubExportGuard extends CompanyDashboardExportGuard {
        private final Map<Long, CoreDatasetTableField> fields;
        private final Map<Long, CoreChartView> charts;

        StubExportGuard(CompanyDashboardGuard dashboardGuard,
                        Map<Long, CoreDatasetTableField> fields,
                        Map<Long, CoreChartView> charts) {
            super(null, null, dashboardGuard);
            this.fields = fields;
            this.charts = charts;
        }

        @Override
        protected CoreChartView loadChart(Long chartId) {
            return chartId == null ? null : charts.get(chartId);
        }

        @Override
        protected CoreDatasetTableField loadField(Long fieldId) {
            return fieldId == null ? null : fields.get(fieldId);
        }
    }

    private static BusiNodeVO node(Long id, String name, boolean leaf, List<BusiNodeVO> children) {
        BusiNodeVO vo = new BusiNodeVO();
        vo.setId(id);
        vo.setName(name);
        vo.setLeaf(leaf);
        vo.setWeight(9);
        vo.setChildren(children);
        return vo;
    }

    private static CompanyBiUser enabled(long id, CompanyAccountRole role) {
        CompanyBiUser user = new CompanyBiUser();
        user.setId(id);
        user.setUsername("u" + id);
        user.setAccountRole(role.name());
        user.setStatus(CompanyUserStatus.ENABLED.name());
        return user;
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
    }

    static final class FakeAclRepository extends CompanyAclRepository {
        private final Map<String, String> rows = new HashMap<>();

        FakeAclRepository() {
            super(null);
        }

        void grant(CompanySubjectType subjectType, Long subjectId, Long datasetId) {
            insertIfAbsent(subjectType, subjectId, datasetId, subjectId);
        }

        void grantDashboard(CompanySubjectType subjectType, Long subjectId, Long dashboardId, CompanyRelationLevel level) {
            insertIfAbsentDashboard(subjectType, subjectId, dashboardId, level, subjectId);
        }

        @Override
        public boolean existsDatasetAcl(CompanySubjectType subjectType, Long subjectId, Long datasetId) {
            return rows.containsKey(key(subjectType, subjectId, CompanyResourceType.DATASET, datasetId));
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
        public boolean insertIfAbsent(CompanySubjectType subjectType, Long subjectId, Long datasetId, Long createdBy) {
            String k = key(subjectType, subjectId, CompanyResourceType.DATASET, datasetId);
            if (rows.containsKey(k)) {
                return false;
            }
            rows.put(k, CompanyRelationLevel.VISITOR.name());
            return true;
        }

        @Override
        public boolean insertIfAbsentDashboard(CompanySubjectType subjectType, Long subjectId, Long dashboardId,
                                               CompanyRelationLevel relationLevel, Long createdBy) {
            String k = key(subjectType, subjectId, CompanyResourceType.DASHBOARD, dashboardId);
            if (rows.containsKey(k)) {
                return false;
            }
            rows.put(k, relationLevel.name());
            return true;
        }

        @Override
        public boolean insertIfAbsentUserDashboardVisitor(Long userId, Long dashboardId, Long createdBy) {
            return insertIfAbsentDashboard(CompanySubjectType.USER, userId, dashboardId, CompanyRelationLevel.VISITOR, createdBy);
        }

        @Override
        public boolean existsDashboardAcl(CompanySubjectType subjectType, Long subjectId, Long dashboardId) {
            return rows.containsKey(key(subjectType, subjectId, CompanyResourceType.DASHBOARD, dashboardId));
        }

        @Override
        public List<CompanyBiResourceAcl> listDashboardAcls(Long userId, Collection<Long> groupIds) {
            List<CompanyBiResourceAcl> out = new ArrayList<>();
            collect(out, CompanySubjectType.USER, userId, CompanyResourceType.DASHBOARD);
            if (groupIds != null) {
                for (Long groupId : groupIds) {
                    collect(out, CompanySubjectType.GROUP, groupId, CompanyResourceType.DASHBOARD);
                }
            }
            return out;
        }

        @Override
        public List<CompanyBiResourceAcl> listFolderAcls(Long userId, Collection<Long> groupIds) {
            List<CompanyBiResourceAcl> out = new ArrayList<>();
            collect(out, CompanySubjectType.USER, userId, CompanyResourceType.DASHBOARD_FOLDER);
            if (groupIds != null) {
                for (Long groupId : groupIds) {
                    collect(out, CompanySubjectType.GROUP, groupId, CompanyResourceType.DASHBOARD_FOLDER);
                }
            }
            return out;
        }

        @Override
        public boolean insertIfAbsentFolder(CompanySubjectType subjectType, Long subjectId, Long folderId,
                                            CompanyRelationLevel relationLevel, Long createdBy) {
            String k = key(subjectType, subjectId, CompanyResourceType.DASHBOARD_FOLDER, folderId);
            if (rows.containsKey(k)) {
                return false;
            }
            rows.put(k, relationLevel.name());
            return true;
        }

        @Override
        public boolean insertIfAbsentUserFolderVisitor(Long userId, Long folderId, Long createdBy) {
            return insertIfAbsentFolder(CompanySubjectType.USER, userId, folderId, CompanyRelationLevel.VISITOR, createdBy);
        }

        @Override
        public void deleteAclsForResources(Collection<Long> resourceIds) {
            if (resourceIds == null) {
                return;
            }
            rows.keySet().removeIf(k -> {
                int last = k.lastIndexOf(':');
                Long id = Long.valueOf(k.substring(last + 1));
                return resourceIds.contains(id);
            });
        }

        private void collect(List<CompanyBiResourceAcl> out, CompanySubjectType subjectType, Long subjectId,
                             CompanyResourceType resourceType) {
            String prefix = subjectType + ":" + subjectId + ":" + resourceType + ":";
            for (Map.Entry<String, String> entry : rows.entrySet()) {
                if (!entry.getKey().startsWith(prefix)) {
                    continue;
                }
                Long resourceId = Long.valueOf(entry.getKey().substring(prefix.length()));
                CompanyBiResourceAcl row = new CompanyBiResourceAcl();
                row.setSubjectType(subjectType.name());
                row.setSubjectId(subjectId);
                row.setResourceType(resourceType.name());
                row.setResourceId(resourceId);
                row.setRelationLevel(entry.getValue());
                out.add(row);
            }
        }

        private static String key(CompanySubjectType subjectType, Long subjectId, CompanyResourceType type, Long resourceId) {
            return subjectType + ":" + subjectId + ":" + type + ":" + resourceId;
        }
    }

    static final class FakeGroupRepository extends CompanyGroupRepository {
        private final Map<Long, Set<Long>> members = new HashMap<>();
        private final Set<Long> enabledGroups = new HashSet<>();

        FakeGroupRepository() {
            super(null, null);
        }

        void addMember(Long userId, Long groupId, boolean enabled) {
            members.computeIfAbsent(userId, ignored -> new HashSet<>()).add(groupId);
            if (enabled) {
                enabledGroups.add(groupId);
            } else {
                enabledGroups.remove(groupId);
            }
        }

        @Override
        public List<Long> listEnabledGroupIds(Long userId) {
            Set<Long> owned = members.get(userId);
            if (owned == null) {
                return List.of();
            }
            List<Long> ids = new ArrayList<>();
            for (Long groupId : owned) {
                if (enabledGroups.contains(groupId)) {
                    ids.add(groupId);
                }
            }
            return ids;
        }
    }
}
