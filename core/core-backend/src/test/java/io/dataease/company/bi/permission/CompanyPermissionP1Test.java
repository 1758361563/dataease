package io.dataease.company.bi.permission;

import io.dataease.api.permissions.auth.dto.BusiPerCheckDTO;
import io.dataease.auth.bo.TokenUserBO;
import io.dataease.company.bi.acl.CompanyAclRepository;
import io.dataease.company.bi.acl.CompanyRelationLevel;
import io.dataease.company.bi.acl.CompanyResourceType;
import io.dataease.company.bi.acl.CompanySubjectType;
import io.dataease.company.bi.group.CompanyGroupRepository;
import io.dataease.company.bi.user.CompanyAccountRole;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.company.bi.user.CompanyUserStatus;
import io.dataease.constant.AuthEnum;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import io.dataease.system.manage.CorePermissionManage;
import io.dataease.utils.AuthUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyPermissionP1Test {

    private static final long USER_U = 100L;
    private static final long USER_ADMIN = 1L;
    private static final long USER_READONLY = 200L;
    private static final long DATASET_X = 9001L;
    private static final long DATASOURCE_Y = 8001L;
    private static final long GROUP_G = 501L;

    private FakeUserService users;
    private FakeAclRepository acl;
    private FakeGroupRepository groups;
    private PermissionDecisionService decision;
    private CompanyCheckAuthAdapter adapter;
    private CompanyDatasetDesignGuard designGuard;

    @BeforeEach
    void setUp() {
        users = new FakeUserService();
        acl = new FakeAclRepository();
        groups = new FakeGroupRepository();
        DatasetPermissionResolver resolver = new DatasetPermissionResolver(acl, groups);
        decision = new PermissionDecisionService(users, resolver);
        FakeLookup lookup = new FakeLookup();
        lookup.put(DATASET_X, CompanyResourceTypeLookup.Kind.DATASET);
        lookup.put(DATASOURCE_Y, CompanyResourceTypeLookup.Kind.DATASOURCE);
        adapter = new CompanyCheckAuthAdapter(lookup, decision);
        designGuard = new CompanyDatasetDesignGuard(decision);
        users.put(enabled(USER_U, CompanyAccountRole.NORMAL));
        users.put(enabled(USER_ADMIN, CompanyAccountRole.ADMIN));
        users.put(enabled(USER_READONLY, CompanyAccountRole.READONLY));
    }

    @AfterEach
    void tearDown() {
        AuthUtils.remove();
    }

    @Test
    void p1T1UserDirectDatasetReadTrue() {
        acl.grant(CompanySubjectType.USER, USER_U, DATASET_X);
        assertTrue(decision.hasDatasetRead(USER_U, DATASET_X));
    }

    @Test
    void p1T2NoAclFalse() {
        assertFalse(decision.hasDatasetRead(USER_U, DATASET_X));
        assertFalse(decision.hasDatasetRead(99L, DATASET_X));
    }

    @Test
    void p1T3GroupDatasetReadTrue() {
        groups.addMember(USER_U, GROUP_G, true);
        acl.grant(CompanySubjectType.GROUP, GROUP_G, DATASET_X);
        assertTrue(decision.hasDatasetRead(USER_U, DATASET_X));
    }

    @Test
    void p1T4RemoveFromGroupFalse() {
        groups.addMember(USER_U, GROUP_G, true);
        acl.grant(CompanySubjectType.GROUP, GROUP_G, DATASET_X);
        assertTrue(decision.hasDatasetRead(USER_U, DATASET_X));
        groups.deleteMember(USER_U, GROUP_G);
        assertFalse(decision.hasDatasetRead(USER_U, DATASET_X));
    }

    @Test
    void p1T5DisabledUserFalseEvenWithAcl() {
        acl.grant(CompanySubjectType.USER, USER_U, DATASET_X);
        users.byId.get(USER_U).setStatus(CompanyUserStatus.DISABLED.name());
        assertFalse(decision.hasDatasetRead(USER_U, DATASET_X));
    }

    @Test
    void p1T6CheckAuthDatasetHonorsRead() {
        AuthUtils.setUser(new TokenUserBO(USER_U, 1L));
        BusiPerCheckDTO dto = datasetDto();
        assertFalse(adapter.checkAuth(dto));
        acl.grant(CompanySubjectType.USER, USER_U, DATASET_X);
        assertTrue(adapter.checkAuth(dto));
        CorePermissionManage manage = new CorePermissionManage(provider(adapter));
        assertTrue(manage.checkAuth(dto));
        acl.deleteDatasetAcl(CompanySubjectType.USER, USER_U, DATASET_X);
        assertFalse(manage.checkAuth(dto));
    }

    @Test
    void p1T7CheckAuthDatasourceRuntimeTrue() {
        AuthUtils.setUser(new TokenUserBO(USER_U, 1L));
        BusiPerCheckDTO dto = new BusiPerCheckDTO();
        dto.setId(DATASOURCE_Y);
        dto.setAuthEnum(AuthEnum.READ);
        assertTrue(adapter.checkAuth(dto));
        CorePermissionManage manage = new CorePermissionManage(provider(adapter));
        assertTrue(manage.checkAuth(dto));
        assertFalse(adapter.checkAuth(unknownDto()));
    }

    @Test
    void p1T8NormalDatasetDesignFalse() {
        assertFalse(decision.hasCapability(USER_U, CompanyCapability.DATASET_DESIGN));
    }

    @Test
    void p1T9AdminDatasetDesignTrue() {
        assertTrue(decision.hasCapability(USER_ADMIN, CompanyCapability.DATASET_DESIGN));
    }

    @Test
    void p1T10DesignerApiNormalForbidden() {
        AuthUtils.setUser(new TokenUserBO(USER_U, 1L));
        DEException ex = assertThrows(DEException.class, () -> designGuard.requireDatasetDesign());
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p1T11DesignerApiReadonlyForbidden() {
        AuthUtils.setUser(new TokenUserBO(USER_READONLY, 1L));
        DEException ex = assertThrows(DEException.class, () -> designGuard.requireDatasetDesign());
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p1T12DesignerApiAdminAllowed() {
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        designGuard.requireDatasetDesign();
        assertTrue(CompanyDatasetDesignFilter.isDesignEndpoint("/datasetData/previewSql", "/de2api/datasetData/previewSql"));
        assertTrue(CompanyDatasetDesignFilter.isDesignEndpoint("/datasetData/enumValueDs", null));
        assertTrue(CompanyDatasetDesignFilter.isDesignEndpoint("/datasetData/getDatasetCount", "/de2api/datasetData/getDatasetCount"));
        assertTrue(CompanyDatasetDesignFilter.isDesignEndpoint("/datasetData/getDatasetTotal", "/de2api/datasetData/getDatasetTotal"));
        assertTrue(CompanyDatasetDesignFilter.isDesignEndpoint("/datasetTree/save", "/de2api/datasetTree/save"));
        assertTrue(CompanyDatasetDesignFilter.isDesignEndpoint("/datasetTree/create", "/de2api/datasetTree/create"));
        assertTrue(CompanyDatasetDesignFilter.isDesignEndpoint("/datasetTree/get/985189053949415424", null));
        assertTrue(CompanyDatasetDesignFilter.isDesignEndpoint("/datasetTree/delete/1", "/de2api/datasetTree/delete/1"));
        assertFalse(CompanyDatasetDesignFilter.isDesignEndpoint("/datasetData/enumValue", "/de2api/datasetData/enumValue"));
        assertFalse(CompanyDatasetDesignFilter.isDesignEndpoint("/datasetData/getFieldTree", "/de2api/datasetData/getFieldTree"));
        assertFalse(CompanyDatasetDesignFilter.isDesignEndpoint("/datasetTree/getSqlParams", "/de2api/datasetTree/getSqlParams"));
        assertFalse(CompanyDatasetDesignFilter.isDesignEndpoint("/datasetTree/details/1", "/de2api/datasetTree/details/1"));
        assertTrue(CompanyDatasetDesignFilter.isDesignEndpoint("/de2api/datasetData/getDatasetTotal", "/de2api/datasetData/getDatasetTotal"));
        assertTrue(CompanyDatasetDesignFilter.isDesignEndpoint("/de2api/datasetTree/get/1", "/de2api/datasetTree/get/1"));
        assertFalse(CompanyDatasetDesignFilter.isDesignEndpoint("/de2api/datasetTree/getSqlParams", "/de2api/datasetTree/getSqlParams"));
    }

    @Test
    void p1C1CountTotalWithoutReadIsDesignerDeny() {
        AuthUtils.setUser(new TokenUserBO(USER_U, 1L));
        assertFalse(decision.hasDatasetRead(USER_U, DATASET_X));
        DEException ex = assertThrows(DEException.class, () -> designGuard.requireDatasetDesign());
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
        assertTrue(CompanyDatasetDesignFilter.matches("/datasetData/getDatasetCount"));
        assertTrue(CompanyDatasetDesignFilter.matches("/datasetData/getDatasetTotal"));
    }

    @Test
    void p1C2CountTotalIsDesignerNotRuntimeRead() {
        acl.grant(CompanySubjectType.USER, USER_U, DATASET_X);
        assertTrue(decision.hasDatasetRead(USER_U, DATASET_X));
        AuthUtils.setUser(new TokenUserBO(USER_U, 1L));
        DEException ex = assertThrows(DEException.class, () -> designGuard.requireDatasetDesign());
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        designGuard.requireDatasetDesign();
    }

    @Test
    void p1C3NormalDatasetCrudGuardDeny() {
        AuthUtils.setUser(new TokenUserBO(USER_U, 1L));
        DEException ex = assertThrows(DEException.class, () -> designGuard.requireDatasetDesign());
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
        assertTrue(CompanyDatasetDesignFilter.matches("/datasetTree/save"));
        assertTrue(CompanyDatasetDesignFilter.matches("/datasetTree/create"));
        assertTrue(CompanyDatasetDesignFilter.matches("/datasetTree/rename"));
        assertTrue(CompanyDatasetDesignFilter.matches("/datasetTree/get/1"));
    }

    @Test
    void p1C4ReadonlyDatasetCrudGuardDeny() {
        AuthUtils.setUser(new TokenUserBO(USER_READONLY, 1L));
        DEException ex = assertThrows(DEException.class, () -> designGuard.requireDatasetDesign());
        assertEquals(ResultCode.PERMISSION_NO_ACCESS.code(), ex.getCode());
    }

    @Test
    void p1C5AdminDatasetDesignerGuardAllow() {
        AuthUtils.setUser(new TokenUserBO(USER_ADMIN, 1L));
        designGuard.requireDatasetDesign();
        assertTrue(decision.hasCapability(USER_ADMIN, CompanyCapability.DATASET_DESIGN));
    }

    @Test
    void disabledGroupDoesNotGrantDatasetRead() {
        groups.addMember(USER_U, GROUP_G, false);
        acl.grant(CompanySubjectType.GROUP, GROUP_G, DATASET_X);
        assertFalse(decision.hasDatasetRead(USER_U, DATASET_X));
    }

    @Test
    void seedIsIdempotentAndWritesVisitorAcl() {
        FakeAclRepository seedAcl = new FakeAclRepository();
        boolean first = seedAcl.insertIfAbsentUserDatasetRead(USER_ADMIN, DATASET_X, USER_ADMIN);
        boolean second = seedAcl.insertIfAbsentUserDatasetRead(USER_ADMIN, DATASET_X, USER_ADMIN);
        assertTrue(first);
        assertFalse(second);
        assertTrue(seedAcl.existsDatasetAcl(CompanySubjectType.USER, USER_ADMIN, DATASET_X));
        assertEquals(CompanyRelationLevel.VISITOR.name(), seedAcl.relationOf(CompanySubjectType.USER, USER_ADMIN, DATASET_X));
    }

    private static BusiPerCheckDTO datasetDto() {
        BusiPerCheckDTO dto = new BusiPerCheckDTO();
        dto.setId(DATASET_X);
        dto.setAuthEnum(AuthEnum.READ);
        return dto;
    }

    private static BusiPerCheckDTO unknownDto() {
        BusiPerCheckDTO dto = new BusiPerCheckDTO();
        dto.setId(1L);
        dto.setAuthEnum(AuthEnum.READ);
        return dto;
    }

    private static CompanyBiUser enabled(long id, CompanyAccountRole role) {
        CompanyBiUser user = new CompanyBiUser();
        user.setId(id);
        user.setUsername("u" + id);
        user.setAccountRole(role.name());
        user.setStatus(CompanyUserStatus.ENABLED.name());
        return user;
    }

    private static ObjectProvider<CompanyCheckAuthAdapter> provider(CompanyCheckAuthAdapter adapter) {
        return new ObjectProvider<>() {
            @Override
            public CompanyCheckAuthAdapter getObject() {
                return adapter;
            }

            @Override
            public CompanyCheckAuthAdapter getObject(Object... args) {
                return adapter;
            }

            @Override
            public CompanyCheckAuthAdapter getIfAvailable() {
                return adapter;
            }

            @Override
            public CompanyCheckAuthAdapter getIfUnique() {
                return adapter;
            }
        };
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

    static final class FakeLookup extends CompanyResourceTypeLookup {
        private final Map<Long, Kind> kinds = new HashMap<>();

        FakeLookup() {
            super(null, null);
        }

        void put(Long id, Kind kind) {
            kinds.put(id, kind);
        }

        @Override
        public Kind identify(Long id) {
            return kinds.getOrDefault(id, Kind.UNKNOWN);
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

        String relationOf(CompanySubjectType subjectType, Long subjectId, Long datasetId) {
            return rows.get(key(subjectType, subjectId, datasetId));
        }

        @Override
        public boolean existsDatasetAcl(CompanySubjectType subjectType, Long subjectId, Long datasetId) {
            return rows.containsKey(key(subjectType, subjectId, datasetId));
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
            String k = key(subjectType, subjectId, datasetId);
            if (rows.containsKey(k)) {
                return false;
            }
            rows.put(k, CompanyRelationLevel.VISITOR.name());
            return true;
        }

        @Override
        public void deleteDatasetAcl(CompanySubjectType subjectType, Long subjectId, Long datasetId) {
            rows.remove(key(subjectType, subjectId, datasetId));
        }

        private static String key(CompanySubjectType subjectType, Long subjectId, Long datasetId) {
            return subjectType + ":" + subjectId + ":" + CompanyResourceType.DATASET + ":" + datasetId;
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

        @Override
        public void deleteMember(Long userId, Long groupId) {
            Set<Long> owned = members.get(userId);
            if (owned != null) {
                owned.remove(groupId);
            }
        }
    }

    static {
        Objects.requireNonNull(CompanyResourceType.DATASET);
    }
}
