package io.dataease.company.bi.permission;

import io.dataease.company.bi.acl.CompanyAclRepository;
import io.dataease.company.bi.acl.CompanyBiResourceAcl;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.group.CompanyGroupRepository;
import io.dataease.company.bi.user.CompanyBiUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class DashboardPermissionResolver {

    private final CompanyAclRepository aclRepository;
    private final CompanyGroupRepository groupRepository;
    private final VisualizationHierarchySource hierarchySource;

    public DashboardPermissionResolver(CompanyAclRepository aclRepository, CompanyGroupRepository groupRepository) {
        this(aclRepository, groupRepository, VisualizationHierarchy::empty);
    }

    @Autowired
    public DashboardPermissionResolver(CompanyAclRepository aclRepository,
                                       CompanyGroupRepository groupRepository,
                                       VisualizationHierarchySource hierarchySource) {
        this.aclRepository = aclRepository;
        this.groupRepository = groupRepository;
        this.hierarchySource = hierarchySource == null ? VisualizationHierarchy::empty : hierarchySource;
    }

    public CompanyResourceRelation resolveDashboardRelation(CompanyBiUser user, Long dashboardId) {
        if (user == null || !user.isEnabled() || dashboardId == null) {
            return CompanyResourceRelation.NONE;
        }
        return snapshot(user.getId()).dashboards.getOrDefault(dashboardId, CompanyResourceRelation.NONE);
    }

    public CompanyResourceRelation resolveFolderRelation(CompanyBiUser user, Long folderId) {
        if (user == null || !user.isEnabled() || folderId == null) {
            return CompanyResourceRelation.NONE;
        }
        return snapshot(user.getId()).folders.getOrDefault(folderId, CompanyResourceRelation.NONE);
    }

    public boolean hasDashboardAccess(CompanyBiUser user, Long dashboardId) {
        return resolveDashboardRelation(user, dashboardId).hasAccess();
    }

    public boolean hasFolderAccess(CompanyBiUser user, Long folderId) {
        return resolveFolderRelation(user, folderId).hasAccess();
    }

    public boolean isFolder(Long id) {
        return hierarchySource.load().isFolder(id);
    }

    public VisualizationHierarchy hierarchy() {
        return hierarchySource.load();
    }

    public Map<Long, CompanyResourceRelation> mapAccessibleDashboardRelations(Long userId) {
        if (userId == null) {
            return Map.of();
        }
        return snapshot(userId).dashboards;
    }

    public Map<Long, CompanyResourceRelation> mapAccessibleFolderRelations(Long userId) {
        if (userId == null) {
            return Map.of();
        }
        return snapshot(userId).folders;
    }

    public Map<Long, CompanyResourceRelation> mapAccessibleRelations(Long userId) {
        return mapAccessibleDashboardRelations(userId);
    }

    public Set<Long> accessibleDashboardIds(Long userId) {
        return mapAccessibleDashboardRelations(userId).keySet();
    }

    public Set<Long> accessibleFolderIds(Long userId) {
        return mapAccessibleFolderRelations(userId).keySet();
    }

    private Snapshot snapshot(Long userId) {
        VisualizationHierarchy hierarchy = hierarchySource.load();
        List<Long> groupIds = groupRepository.listEnabledGroupIds(userId);
        Map<Long, CompanyResourceRelation> folderDirect = merge(aclRepository.listFolderAcls(userId, groupIds));
        Map<Long, CompanyResourceRelation> dashDirect = merge(aclRepository.listDashboardAcls(userId, groupIds));
        Map<Long, CompanyResourceRelation> folderEffective = new HashMap<>();
        for (Long folderId : hierarchy.folderIds()) {
            CompanyResourceRelation relation = inheritedFolder(folderId, hierarchy, folderDirect, new HashSet<>());
            if (relation.hasAccess()) {
                folderEffective.put(folderId, relation);
            }
        }
        for (Map.Entry<Long, CompanyResourceRelation> entry : folderDirect.entrySet()) {
            if (entry.getValue().hasAccess()) {
                folderEffective.merge(entry.getKey(), entry.getValue(), CompanyResourceRelation::max);
            }
        }
        Map<Long, CompanyResourceRelation> dashEffective = new HashMap<>();
        for (Map.Entry<Long, CompanyResourceRelation> entry : dashDirect.entrySet()) {
            if (entry.getValue().hasAccess()) {
                dashEffective.put(entry.getKey(), entry.getValue());
            }
        }
        for (Long dashboardId : hierarchy.dashboardLeafIds()) {
            CompanyResourceRelation direct = dashDirect.getOrDefault(dashboardId, CompanyResourceRelation.NONE);
            Long parentId = hierarchy.parentId(dashboardId);
            CompanyResourceRelation inherited = parentId == null
                    ? CompanyResourceRelation.NONE
                    : folderEffective.getOrDefault(parentId, CompanyResourceRelation.NONE);
            CompanyResourceRelation effective = CompanyResourceRelation.max(direct, inherited);
            if (effective.hasAccess()) {
                dashEffective.put(dashboardId, effective);
            }
        }
        return new Snapshot(dashEffective, folderEffective);
    }

    private static CompanyResourceRelation inheritedFolder(Long folderId,
                                                           VisualizationHierarchy hierarchy,
                                                           Map<Long, CompanyResourceRelation> folderDirect,
                                                           Set<Long> visiting) {
        if (folderId == null || !visiting.add(folderId) || visiting.size() > VisualizationHierarchy.MAX_DEPTH) {
            return CompanyResourceRelation.NONE;
        }
        CompanyResourceRelation own = folderDirect.getOrDefault(folderId, CompanyResourceRelation.NONE);
        Long parentId = hierarchy.parentId(folderId);
        if (parentId == null || !hierarchy.isFolder(parentId)) {
            return own;
        }
        return CompanyResourceRelation.max(own, inheritedFolder(parentId, hierarchy, folderDirect, visiting));
    }

    private static Map<Long, CompanyResourceRelation> merge(List<CompanyBiResourceAcl> rows) {
        Map<Long, CompanyResourceRelation> merged = new HashMap<>();
        if (rows == null) {
            return merged;
        }
        for (CompanyBiResourceAcl row : rows) {
            if (row == null || row.getResourceId() == null) {
                continue;
            }
            CompanyResourceRelation next = CompanyResourceRelation.fromLevel(row.getRelationLevel());
            merged.merge(row.getResourceId(), next, CompanyResourceRelation::max);
        }
        return merged;
    }

    private record Snapshot(Map<Long, CompanyResourceRelation> dashboards,
                            Map<Long, CompanyResourceRelation> folders) {
    }
}
