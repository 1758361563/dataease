package io.dataease.company.bi.permission;

import io.dataease.auth.bo.TokenUserBO;
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

class CompanyPermissionP3Test {

    private static final long USER_U = 100L;
    private static final long USER_ADMIN = 1L;
    private static final long GROUP_G = 501L;
    private static final long GROUP_B = 502L;
    private static final long FOLDER_A = 8100L;
    private static final long FOLDER_B = 8101L;
    private static final long FOLDER_C = 8102L;
    private static final long DASH_X = 8201L;
    private static final long DASH_Y = 8202L;
    private static final long DATASET_Y = 9301L;

    private FakeUserService users;
    private FakeAclRepository acl;
    private FakeGroupRepository groups;
    private FakeHierarchySource hierarchy;
    private PermissionDecisionService decision;
    private CompanyDashboardGuard guard;
    private CompanyDashboardMutationSupport mutation;
    private CompanyDatasetMutationSupport datasetMutation;
    private DashboardPermissionProjectionService projectionService;

    @BeforeEach
    void setUp() {
        users = new FakeUserService();
        acl = new FakeAclRepository();
        groups = new FakeGroupRepository();
        hierarchy = new FakeHierarchySource();
        hierarchy.folder(FOLDER_A, 0L);
        hierarchy.folder(FOLDER_B, FOLDER_A);
        hierarchy.dash(DASH_X, FOLDER_B);
        hierarchy.dash(DASH_Y, FOLDER_B);
        DatasetPermissionResolver datasetResolver = new DatasetPermissionResolver(acl, groups);
        DashboardPermissionResolver dashboardResolver = new DashboardPermissionResolver(acl, groups, hierarchy);
        CompanyExportPolicy exportPolicy = new CompanyExportPolicy(0, 0);
        projectionService = new DashboardPermissionProjectionService(exportPolicy);
        decision = new PermissionDecisionService(users, datasetResolver, dashboardResolver, projectionService, exportPolicy);
        guard = new CompanyDashboardGuard(decision);
        mutation = new CompanyDashboardMutationSupport(guard, decision, acl);
        datasetMutation = new CompanyDatasetMutationSupport(acl);
        users.put(enabled(USER_U, CompanyAccountRole.NORMAL));
        users.put(enabled(USER_ADMIN, CompanyAccountRole.ADMIN));
    }

    @AfterEach
    void tearDown() {
        AuthUtils.remove();
    }

    @Test
    void p3T1UserFolderVisitorDescendantDashboard() {
        acl.grantFolder(CompanySubjectType.USER, USER_U, FOLDER_A, CompanyRelationLevel.VISITOR);
        assertTrue(decision.hasDashboardAccess(USER_U, DASH_X));
        assertTrue(decision.hasDashboardAccess(USER_U, DASH_Y));
        assertTrue(decision.hasFolderAccess(USER_U, FOLDER_B));
    }

    @Test
    void p3T2GroupFolderVisitorDescendantDashboard() {
        groups.addMember(USER_U, GROUP_G, true);
        acl.grantFolder(CompanySubjectType.GROUP, GROUP_G, FOLDER_A, CompanyRelationLevel.VISITOR);
        assertTrue(decision.hasDashboardAccess(USER_U, DASH_X));
    }

    @Test
    void p3T3NestedFolderInheritance() {
        acl.grantFolder(CompanySubjectType.USER, USER_U, FOLDER_A, CompanyRelationLevel.VISITOR);
        assertEquals(CompanyResourceRelation.VISITOR, decision.resolveFolderRelation(USER_U, FOLDER_B));
        assertTrue(decision.hasDashboardAccess(USER_U, DASH_X));
    }

    @Test
    void p3T4DeepDirectAclAncestorShellSiblingHidden() {
        acl.grantDashboard(CompanySubjectType.USER, USER_U, DASH_X, CompanyRelationLevel.VISITOR);
        assertTrue(decision.hasDashboardAccess(USER_U, DASH_X));
        assertFalse(decision.hasDashboardAccess(USER_U, DASH_Y));
        assertFalse(decision.hasFolderAccess(USER_U, FOLDER_A));
        assertFalse(decision.hasFolderAccess(USER_U, FOLDER_B));
        BusiNodeVO pruned = pruneTree(USER_U, CompanyAccountRole.NORMAL);
        BusiNodeVO folderA = pruned.getChildren().get(0);
        assertEquals(FOLDER_A, folderA.getId());
        assertEquals(1, folderA.getWeight());
        BusiNodeVO folderB = folderA.getChildren().get(0);
        assertEquals(1, folderB.getWeight());
        assertEquals(1, folderB.getChildren().size());
        assertEquals(DASH_X, folderB.getChildren().get(0).getId());
    }

    @Test
    void p3T5FolderVisitorChildOwnerKeepsOwner() {
        acl.grantFolder(CompanySubjectType.USER, USER_U, FOLDER_A, CompanyRelationLevel.VISITOR);
        acl.grantDashboard(CompanySubjectType.USER, USER_U, DASH_X, CompanyRelationLevel.OWNER);
        assertEquals(CompanyResourceRelation.OWNER, decision.resolveDashboardRelation(USER_U, DASH_X));
        assertEquals(CompanyResourceRelation.VISITOR, decision.resolveDashboardRelation(USER_U, DASH_Y));
    }

    @Test
    void p3T6FolderOwnerChildVisitorStaysOwner() {
        acl.grantFolder(CompanySubjectType.USER, USER_U, FOLDER_A, CompanyRelationLevel.OWNER);
        acl.grantDashboard(CompanySubjectType.USER, USER_U, DASH_X, CompanyRelationLevel.VISITOR);
        assertEquals(CompanyResourceRelation.OWNER, decision.resolveDashboardRelation(USER_U, DASH_X));
    }

    @Test
    void p3T7MultipleGroupsMaxRelation() {
        groups.addMember(USER_U, GROUP_G, true);
        groups.addMember(USER_U, GROUP_B, true);
        acl.grantFolder(CompanySubjectType.GROUP, GROUP_G, FOLDER_A, CompanyRelationLevel.VISITOR);
        acl.grantDashboard(CompanySubjectType.GROUP, GROUP_B, DASH_X, CompanyRelationLevel.OWNER);
        assertEquals(CompanyResourceRelation.OWNER, decision.resolveDashboardRelation(USER_U, DASH_X));
        assertEquals(CompanyResourceRelation.VISITOR, decision.resolveDashboardRelation(USER_U, DASH_Y));
    }

    @Test
    void p3T8DisabledGroupDoesNotInherit() {
        groups.addMember(USER_U, GROUP_G, false);
        acl.grantFolder(CompanySubjectType.GROUP, GROUP_G, FOLDER_A, CompanyRelationLevel.VISITOR);
        assertFalse(decision.hasDashboardAccess(USER_U, DASH_X));
    }

    @Test
    void p3T9RemoveUserFromGroupLosesAccess() {
        groups.addMember(USER_U, GROUP_G, true);
        acl.grantFolder(CompanySubjectType.GROUP, GROUP_G, FOLDER_A, CompanyRelationLevel.VISITOR);
        assertTrue(decision.hasDashboardAccess(USER_U, DASH_X));
        groups.removeMember(USER_U, GROUP_G);
        assertFalse(decision.hasDashboardAccess(USER_U, DASH_X));
    }

    @Test
    void p3T10AdminFolderVisitorCanEditDescendant() {
        acl.grantFolder(CompanySubjectType.USER, USER_ADMIN, FOLDER_A, CompanyRelationLevel.VISITOR);
        assertTrue(decision.canEditDashboard(USER_ADMIN, DASH_X));
        assertTrue(decision.canEditFolder(USER_ADMIN, FOLDER_B));
    }

    @Test
    void p3T11NormalFolderOwnerCannotEdit() {
        acl.grantFolder(CompanySubjectType.USER, USER_U, FOLDER_A, CompanyRelationLevel.OWNER);
        assertTrue(decision.hasDashboardAccess(USER_U, DASH_X));
        assertFalse(decision.canEditDashboard(USER_U, DASH_X));
        assertFalse(decision.canEditFolder(USER_U, FOLDER_A));
    }

    @Test
    void p3T12NavigationShellFolderEditFalse() {
        acl.grantDashboard(CompanySubjectType.USER, USER_ADMIN, DASH_X, CompanyRelationLevel.VISITOR);
        assertFalse(decision.hasFolderAccess(USER_ADMIN, FOLDER_A));
        assertFalse(decision.canEditFolder(USER_ADMIN, FOLDER_A));
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        DEException ex = assertThrows(DEException.class, () -> mutation.requireEditResource(FOLDER_A));
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p3T13MoveOutOfAuthorizedFolderLosesAccess() {
        acl.grantFolder(CompanySubjectType.USER, USER_U, FOLDER_A, CompanyRelationLevel.VISITOR);
        assertTrue(decision.hasDashboardAccess(USER_U, DASH_X));
        hierarchy.setPid(DASH_X, FOLDER_C);
        hierarchy.folder(FOLDER_C, 0L);
        assertFalse(decision.hasDashboardAccess(USER_U, DASH_X));
    }

    @Test
    void p3T14MoveIntoAuthorizedFolderGainsAccess() {
        hierarchy.folder(FOLDER_C, 0L);
        hierarchy.setPid(DASH_X, FOLDER_C);
        acl.grantFolder(CompanySubjectType.USER, USER_U, FOLDER_A, CompanyRelationLevel.VISITOR);
        assertFalse(decision.hasDashboardAccess(USER_U, DASH_X));
        hierarchy.setPid(DASH_X, FOLDER_B);
        assertTrue(decision.hasDashboardAccess(USER_U, DASH_X));
    }

    @Test
    void p3T15DirectDashboardAclSurvivesMove() {
        acl.grantDashboard(CompanySubjectType.USER, USER_U, DASH_X, CompanyRelationLevel.VISITOR);
        hierarchy.folder(FOLDER_C, 0L);
        hierarchy.setPid(DASH_X, FOLDER_C);
        assertTrue(decision.hasDashboardAccess(USER_U, DASH_X));
    }

    @Test
    void p3T16MoveTargetFolderUnauthorizedDeny() {
        acl.grantDashboard(CompanySubjectType.USER, USER_ADMIN, DASH_X, CompanyRelationLevel.VISITOR);
        hierarchy.folder(FOLDER_C, 0L);
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        DEException ex = assertThrows(DEException.class, () -> mutation.requireMove(DASH_X, FOLDER_C));
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p3T17FolderMoveTargetUnauthorizedDeny() {
        acl.grantFolder(CompanySubjectType.USER, USER_ADMIN, FOLDER_B, CompanyRelationLevel.VISITOR);
        hierarchy.folder(FOLDER_C, 0L);
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        DEException ex = assertThrows(DEException.class, () -> mutation.requireMove(FOLDER_B, FOLDER_C));
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p3T18CreateDashboardInAuthorizedFolderGrantsCreatorOwner() {
        acl.grantFolder(CompanySubjectType.USER, USER_ADMIN, FOLDER_A, CompanyRelationLevel.VISITOR);
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        mutation.requireCreateIn(FOLDER_A);
        long created = 8301L;
        mutation.afterCreate(created, "leaf");
        assertTrue(acl.existsDashboardAcl(CompanySubjectType.USER, USER_ADMIN, created));
        assertEquals(CompanyResourceRelation.OWNER, decision.resolveDashboardRelation(USER_ADMIN, created));
    }

    @Test
    void p3T19CreateDashboardInUnauthorizedFolderDeny() {
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        DEException ex = assertThrows(DEException.class, () -> mutation.requireCreateIn(FOLDER_A));
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p3T20RootCreateAdminAllowCreatorAcl() {
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        mutation.requireCreateIn(0L);
        long created = 8302L;
        mutation.afterCreate(created, "leaf");
        assertTrue(acl.existsDashboardAcl(CompanySubjectType.USER, USER_ADMIN, created));
        mutation.afterCreate(8303L, "folder");
        assertTrue(acl.existsFolderAcl(CompanySubjectType.USER, USER_ADMIN, 8303L));
        assertEquals(CompanyResourceRelation.OWNER, decision.resolveDashboardRelation(USER_ADMIN, created));
        assertEquals(CompanyResourceRelation.OWNER, decision.resolveFolderRelation(USER_ADMIN, 8303L));
    }

    @Test
    void p3T24CreatorAclInitializationIsIdempotentAndDatasetFoldersStayOutsideAcl() {
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        mutation.afterCreate(8304L, "leaf");
        mutation.afterCreate(8304L, "leaf");
        assertEquals(CompanyResourceRelation.OWNER, decision.resolveDashboardRelation(USER_ADMIN, 8304L));

        datasetMutation.afterCreate(DATASET_Y, "dataset");
        datasetMutation.afterCreate(DATASET_Y, "dataset");
        assertTrue(decision.hasDatasetRead(USER_ADMIN, DATASET_Y));

        datasetMutation.afterCreate(9302L, "folder");
        assertFalse(acl.existsDatasetAcl(CompanySubjectType.USER, USER_ADMIN, 9302L));
    }

    @Test
    void p3T21FolderCrudNormalOwnerDeny() {
        acl.grantFolder(CompanySubjectType.USER, USER_U, FOLDER_A, CompanyRelationLevel.OWNER);
        AuthUtils.setUser(new TokenUserBO(USER_U, 1L));
        DEException ex = assertThrows(DEException.class, () -> mutation.requireEditResource(FOLDER_A));
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p3T22FolderCrudAdminVisitorAllow() {
        acl.grantFolder(CompanySubjectType.USER, USER_ADMIN, FOLDER_A, CompanyRelationLevel.VISITOR);
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        mutation.requireEditResource(FOLDER_A);
        assertTrue(decision.canEditFolder(USER_ADMIN, FOLDER_A));
    }

    @Test
    void p3T23InheritedDashboardInAccessibleSet() {
        acl.grantFolder(CompanySubjectType.USER, USER_U, FOLDER_A, CompanyRelationLevel.VISITOR);
        assertTrue(decision.accessibleDashboardIds(USER_U).contains(DASH_X));
        assertTrue(decision.accessibleDashboardIds(USER_U).contains(DASH_Y));
    }

    @Test
    void p3T24InheritedDashboardExportUsesP2Policy() {
        acl.grantFolder(CompanySubjectType.USER, USER_U, FOLDER_A, CompanyRelationLevel.VISITOR);
        assertFalse(decision.canExportDashboard(USER_U, DASH_X));
        acl.grantFolder(CompanySubjectType.USER, USER_ADMIN, FOLDER_A, CompanyRelationLevel.VISITOR);
        assertTrue(decision.canExportDashboard(USER_ADMIN, DASH_X));
    }

    @Test
    void p3T25FolderAccessDoesNotBypassDatasetRead() {
        acl.grantFolder(CompanySubjectType.USER, USER_U, FOLDER_A, CompanyRelationLevel.VISITOR);
        assertTrue(decision.hasDashboardAccess(USER_U, DASH_X));
        assertFalse(decision.hasDatasetRead(USER_U, DATASET_Y));
    }

    @Test
    void p3T26DeletedResourceAclRemoved() {
        acl.grantDashboard(CompanySubjectType.USER, USER_U, DASH_X, CompanyRelationLevel.VISITOR);
        acl.grantFolder(CompanySubjectType.USER, USER_U, FOLDER_B, CompanyRelationLevel.VISITOR);
        mutation.cleanupAcls(Set.of(DASH_X, FOLDER_B));
        assertFalse(acl.existsDashboardAcl(CompanySubjectType.USER, USER_U, DASH_X));
        assertFalse(acl.existsFolderAcl(CompanySubjectType.USER, USER_U, FOLDER_B));
    }

    @Test
    void p3AuthorizedFolderWeightAdminNineNormalOne() {
        acl.grantFolder(CompanySubjectType.USER, USER_ADMIN, FOLDER_A, CompanyRelationLevel.VISITOR);
        BusiNodeVO adminTree = pruneTree(USER_ADMIN, CompanyAccountRole.ADMIN);
        assertEquals(9, adminTree.getChildren().get(0).getWeight());
        acl.grantFolder(CompanySubjectType.USER, USER_U, FOLDER_A, CompanyRelationLevel.VISITOR);
        BusiNodeVO normalTree = pruneTree(USER_U, CompanyAccountRole.NORMAL);
        assertEquals(1, normalTree.getChildren().get(0).getWeight());
    }

    @Test
    void p3FolderCycleMoveDenied() {
        acl.grantFolder(CompanySubjectType.USER, USER_ADMIN, FOLDER_A, CompanyRelationLevel.VISITOR);
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        DEException ex = assertThrows(DEException.class, () -> mutation.requireMove(FOLDER_A, FOLDER_B));
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    private BusiNodeVO pruneTree(long userId, CompanyAccountRole role) {
        BusiNodeVO root = node(0L, "root", false);
        BusiNodeVO a = node(FOLDER_A, "A", false);
        BusiNodeVO b = node(FOLDER_B, "B", false);
        b.setChildren(new ArrayList<>(List.of(node(DASH_X, "X", true), node(DASH_Y, "Y", true))));
        a.setChildren(new ArrayList<>(List.of(b)));
        root.setChildren(new ArrayList<>(List.of(a)));
        List<BusiNodeVO> pruned = CompanyDashboardTreePruner.prune(
                List.of(root),
                decision.accessibleDashboardIds(userId),
                decision.accessibleFolderIds(userId),
                projectionService.project(role, true),
                projectionService.authorizedFolder(role),
                projectionService.folderShell());
        return pruned.get(0);
    }

    private static BusiNodeVO node(Long id, String name, boolean leaf) {
        BusiNodeVO vo = new BusiNodeVO();
        vo.setId(id);
        vo.setName(name);
        vo.setLeaf(leaf);
        vo.setWeight(9);
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

    static final class FakeHierarchySource implements VisualizationHierarchySource {
        private final Map<Long, VisualizationHierarchy.Node> nodes = new HashMap<>();

        void folder(Long id, Long pid) {
            nodes.put(id, new VisualizationHierarchy.Node(id, pid, true, false));
        }

        void dash(Long id, Long pid) {
            nodes.put(id, new VisualizationHierarchy.Node(id, pid, false, true));
        }

        void setPid(Long id, Long pid) {
            VisualizationHierarchy.Node current = nodes.get(id);
            if (current == null) {
                return;
            }
            nodes.put(id, new VisualizationHierarchy.Node(id, pid, current.folder(), current.dashboardLeaf()));
        }

        @Override
        public VisualizationHierarchy load() {
            return new VisualizationHierarchy(new ArrayList<>(nodes.values()));
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

        void removeMember(Long userId, Long groupId) {
            Set<Long> owned = members.get(userId);
            if (owned != null) {
                owned.remove(groupId);
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

    static final class FakeAclRepository extends CompanyAclRepository {
        private final Map<String, String> rows = new HashMap<>();

        FakeAclRepository() {
            super(null);
        }

        void grantFolder(CompanySubjectType subjectType, Long subjectId, Long folderId, CompanyRelationLevel level) {
            insertIfAbsentFolder(subjectType, subjectId, folderId, level, subjectId);
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
        public boolean existsDashboardAcl(CompanySubjectType subjectType, Long subjectId, Long dashboardId) {
            return rows.containsKey(key(subjectType, subjectId, CompanyResourceType.DASHBOARD, dashboardId));
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
        public boolean existsFolderAcl(CompanySubjectType subjectType, Long subjectId, Long folderId) {
            return rows.containsKey(key(subjectType, subjectId, CompanyResourceType.DASHBOARD_FOLDER, folderId));
        }

        @Override
        public List<CompanyBiResourceAcl> listDashboardAcls(Long userId, Collection<Long> groupIds) {
            return collect(userId, groupIds, CompanyResourceType.DASHBOARD);
        }

        @Override
        public List<CompanyBiResourceAcl> listFolderAcls(Long userId, Collection<Long> groupIds) {
            return collect(userId, groupIds, CompanyResourceType.DASHBOARD_FOLDER);
        }

        @Override
        public void deleteAclsForResources(Collection<Long> resourceIds) {
            if (resourceIds == null) {
                return;
            }
            rows.keySet().removeIf(k -> resourceIds.contains(Long.valueOf(k.substring(k.lastIndexOf(':') + 1))));
        }

        private List<CompanyBiResourceAcl> collect(Long userId, Collection<Long> groupIds, CompanyResourceType type) {
            List<CompanyBiResourceAcl> out = new ArrayList<>();
            add(out, CompanySubjectType.USER, userId, type);
            if (groupIds != null) {
                for (Long groupId : groupIds) {
                    add(out, CompanySubjectType.GROUP, groupId, type);
                }
            }
            return out;
        }

        private void add(List<CompanyBiResourceAcl> out, CompanySubjectType subjectType, Long subjectId,
                         CompanyResourceType type) {
            String prefix = subjectType + ":" + subjectId + ":" + type + ":";
            for (Map.Entry<String, String> entry : rows.entrySet()) {
                if (!entry.getKey().startsWith(prefix)) {
                    continue;
                }
                CompanyBiResourceAcl row = new CompanyBiResourceAcl();
                row.setSubjectType(subjectType.name());
                row.setSubjectId(subjectId);
                row.setResourceType(type.name());
                row.setResourceId(Long.valueOf(entry.getKey().substring(prefix.length())));
                row.setRelationLevel(entry.getValue());
                out.add(row);
            }
        }

        private static String key(CompanySubjectType subjectType, Long subjectId, CompanyResourceType type, Long resourceId) {
            return subjectType + ":" + subjectId + ":" + type + ":" + resourceId;
        }
    }
}
