package io.dataease.company.bi.acl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import io.dataease.company.bi.auth.CompanyAuthSupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyAclRepository {

    private final CompanyBiResourceAclMapper mapper;

    public CompanyAclRepository(CompanyBiResourceAclMapper mapper) {
        this.mapper = mapper;
    }

    public boolean existsDatasetAcl(CompanySubjectType subjectType, Long subjectId, Long datasetId) {
        if (subjectType == null || subjectId == null || datasetId == null) {
            return false;
        }
        QueryWrapper<CompanyBiResourceAcl> wrapper = new QueryWrapper<>();
        wrapper.eq("subject_type", subjectType.name())
                .eq("subject_id", subjectId)
                .eq("resource_type", CompanyResourceType.DATASET.name())
                .eq("resource_id", datasetId)
                .last("LIMIT 1");
        return mapper.selectCount(wrapper) > 0;
    }

    public boolean existsDatasetAclForGroups(Collection<Long> groupIds, Long datasetId) {
        if (groupIds == null || groupIds.isEmpty() || datasetId == null) {
            return false;
        }
        QueryWrapper<CompanyBiResourceAcl> wrapper = new QueryWrapper<>();
        wrapper.eq("subject_type", CompanySubjectType.GROUP.name())
                .in("subject_id", groupIds)
                .eq("resource_type", CompanyResourceType.DATASET.name())
                .eq("resource_id", datasetId)
                .last("LIMIT 1");
        return mapper.selectCount(wrapper) > 0;
    }

    public boolean insertIfAbsentUserDatasetRead(Long userId, Long datasetId, Long createdBy) {
        return insertIfAbsent(CompanySubjectType.USER, userId, datasetId, createdBy);
    }

    public boolean insertIfAbsent(CompanySubjectType subjectType, Long subjectId, Long datasetId, Long createdBy) {
        if (existsDatasetAcl(subjectType, subjectId, datasetId)) {
            return false;
        }
        long now = System.currentTimeMillis();
        CompanyBiResourceAcl row = new CompanyBiResourceAcl();
        row.setSubjectType(subjectType.name());
        row.setSubjectId(subjectId);
        row.setResourceType(CompanyResourceType.DATASET.name());
        row.setResourceId(datasetId);
        row.setRelationLevel(CompanyRelationLevel.VISITOR.name());
        row.setCreatedAt(now);
        row.setCreatedBy(createdBy);
        row.setUpdatedAt(now);
        row.setUpdatedBy(createdBy);
        try {
            mapper.insert(row);
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    public void deleteDatasetAcl(CompanySubjectType subjectType, Long subjectId, Long datasetId) {
        if (subjectType == null || subjectId == null || datasetId == null) {
            return;
        }
        QueryWrapper<CompanyBiResourceAcl> wrapper = new QueryWrapper<>();
        wrapper.eq("subject_type", subjectType.name())
                .eq("subject_id", subjectId)
                .eq("resource_type", CompanyResourceType.DATASET.name())
                .eq("resource_id", datasetId);
        mapper.delete(wrapper);
    }

    public List<CompanyBiResourceAcl> listByResource(CompanyResourceType resourceType, Long resourceId) {
        QueryWrapper<CompanyBiResourceAcl> wrapper = new QueryWrapper<>();
        wrapper.eq("resource_type", resourceType.name()).eq("resource_id", resourceId);
        return mapper.selectList(wrapper);
    }

    public boolean insertIfAbsentUserDashboardVisitor(Long userId, Long dashboardId, Long createdBy) {
        return insertIfAbsentDashboard(CompanySubjectType.USER, userId, dashboardId, CompanyRelationLevel.VISITOR, createdBy);
    }

    public boolean insertIfAbsentUserDashboardOwner(Long userId, Long dashboardId, Long createdBy) {
        return insertIfAbsentDashboard(CompanySubjectType.USER, userId, dashboardId, CompanyRelationLevel.OWNER, createdBy);
    }

    public boolean insertIfAbsentDashboard(CompanySubjectType subjectType, Long subjectId, Long dashboardId,
                                           CompanyRelationLevel relationLevel, Long createdBy) {
        if (subjectType == null || subjectId == null || dashboardId == null || relationLevel == null) {
            return false;
        }
        if (existsDashboardAcl(subjectType, subjectId, dashboardId)) {
            return false;
        }
        long now = System.currentTimeMillis();
        CompanyBiResourceAcl row = new CompanyBiResourceAcl();
        row.setSubjectType(subjectType.name());
        row.setSubjectId(subjectId);
        row.setResourceType(CompanyResourceType.DASHBOARD.name());
        row.setResourceId(dashboardId);
        row.setRelationLevel(relationLevel.name());
        row.setCreatedAt(now);
        row.setCreatedBy(createdBy);
        row.setUpdatedAt(now);
        row.setUpdatedBy(createdBy);
        try {
            mapper.insert(row);
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    public boolean existsDashboardAcl(CompanySubjectType subjectType, Long subjectId, Long dashboardId) {
        if (subjectType == null || subjectId == null || dashboardId == null) {
            return false;
        }
        QueryWrapper<CompanyBiResourceAcl> wrapper = new QueryWrapper<>();
        wrapper.eq("subject_type", subjectType.name())
                .eq("subject_id", subjectId)
                .eq("resource_type", CompanyResourceType.DASHBOARD.name())
                .eq("resource_id", dashboardId)
                .last("LIMIT 1");
        return mapper.selectCount(wrapper) > 0;
    }

    public void deleteDashboardAcl(CompanySubjectType subjectType, Long subjectId, Long dashboardId) {
        if (subjectType == null || subjectId == null || dashboardId == null) {
            return;
        }
        QueryWrapper<CompanyBiResourceAcl> wrapper = new QueryWrapper<>();
        wrapper.eq("subject_type", subjectType.name())
                .eq("subject_id", subjectId)
                .eq("resource_type", CompanyResourceType.DASHBOARD.name())
                .eq("resource_id", dashboardId);
        mapper.delete(wrapper);
    }

    public List<CompanyBiResourceAcl> listDashboardAcls(Long userId, Collection<Long> groupIds) {
        if (userId == null) {
            return List.of();
        }
        QueryWrapper<CompanyBiResourceAcl> wrapper = new QueryWrapper<>();
        wrapper.eq("resource_type", CompanyResourceType.DASHBOARD.name());
        if (groupIds == null || groupIds.isEmpty()) {
            wrapper.eq("subject_type", CompanySubjectType.USER.name()).eq("subject_id", userId);
        } else {
            List<Long> groups = new ArrayList<>(groupIds);
            wrapper.and(w -> w.nested(u -> u.eq("subject_type", CompanySubjectType.USER.name()).eq("subject_id", userId))
                    .or(g -> g.eq("subject_type", CompanySubjectType.GROUP.name()).in("subject_id", groups)));
        }
        List<CompanyBiResourceAcl> rows = mapper.selectList(wrapper);
        return rows == null ? List.of() : rows;
    }

    public boolean insertIfAbsentUserFolderVisitor(Long userId, Long folderId, Long createdBy) {
        return insertIfAbsentFolder(CompanySubjectType.USER, userId, folderId, CompanyRelationLevel.VISITOR, createdBy);
    }

    public boolean insertIfAbsentUserFolderOwner(Long userId, Long folderId, Long createdBy) {
        return insertIfAbsentFolder(CompanySubjectType.USER, userId, folderId, CompanyRelationLevel.OWNER, createdBy);
    }

    public boolean insertIfAbsentFolder(CompanySubjectType subjectType, Long subjectId, Long folderId,
                                        CompanyRelationLevel relationLevel, Long createdBy) {
        if (subjectType == null || subjectId == null || folderId == null || relationLevel == null) {
            return false;
        }
        if (existsFolderAcl(subjectType, subjectId, folderId)) {
            return false;
        }
        long now = System.currentTimeMillis();
        CompanyBiResourceAcl row = new CompanyBiResourceAcl();
        row.setSubjectType(subjectType.name());
        row.setSubjectId(subjectId);
        row.setResourceType(CompanyResourceType.DASHBOARD_FOLDER.name());
        row.setResourceId(folderId);
        row.setRelationLevel(relationLevel.name());
        row.setCreatedAt(now);
        row.setCreatedBy(createdBy);
        row.setUpdatedAt(now);
        row.setUpdatedBy(createdBy);
        try {
            mapper.insert(row);
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    public boolean existsFolderAcl(CompanySubjectType subjectType, Long subjectId, Long folderId) {
        if (subjectType == null || subjectId == null || folderId == null) {
            return false;
        }
        QueryWrapper<CompanyBiResourceAcl> wrapper = new QueryWrapper<>();
        wrapper.eq("subject_type", subjectType.name())
                .eq("subject_id", subjectId)
                .eq("resource_type", CompanyResourceType.DASHBOARD_FOLDER.name())
                .eq("resource_id", folderId)
                .last("LIMIT 1");
        return mapper.selectCount(wrapper) > 0;
    }

    public List<CompanyBiResourceAcl> listFolderAcls(Long userId, Collection<Long> groupIds) {
        if (userId == null) {
            return List.of();
        }
        QueryWrapper<CompanyBiResourceAcl> wrapper = new QueryWrapper<>();
        wrapper.eq("resource_type", CompanyResourceType.DASHBOARD_FOLDER.name());
        if (groupIds == null || groupIds.isEmpty()) {
            wrapper.eq("subject_type", CompanySubjectType.USER.name()).eq("subject_id", userId);
        } else {
            List<Long> groups = new ArrayList<>(groupIds);
            wrapper.and(w -> w.nested(u -> u.eq("subject_type", CompanySubjectType.USER.name()).eq("subject_id", userId))
                    .or(g -> g.eq("subject_type", CompanySubjectType.GROUP.name()).in("subject_id", groups)));
        }
        List<CompanyBiResourceAcl> rows = mapper.selectList(wrapper);
        return rows == null ? List.of() : rows;
    }

    public void deleteAclsForResources(Collection<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return;
        }
        QueryWrapper<CompanyBiResourceAcl> wrapper = new QueryWrapper<>();
        wrapper.in("resource_type", CompanyResourceType.DASHBOARD.name(), CompanyResourceType.DASHBOARD_FOLDER.name())
                .in("resource_id", resourceIds);
        mapper.delete(wrapper);
    }

    public CompanyBiResourceAcl find(CompanySubjectType subjectType, Long subjectId,
                                     CompanyResourceType resourceType, Long resourceId) {
        if (subjectType == null || subjectId == null || resourceType == null || resourceId == null) {
            return null;
        }
        QueryWrapper<CompanyBiResourceAcl> wrapper = new QueryWrapper<>();
        wrapper.eq("subject_type", subjectType.name())
                .eq("subject_id", subjectId)
                .eq("resource_type", resourceType.name())
                .eq("resource_id", resourceId)
                .last("LIMIT 1");
        return mapper.selectOne(wrapper);
    }

    public List<CompanyBiResourceAcl> listDirectBySubject(CompanySubjectType subjectType, Long subjectId) {
        if (subjectType == null || subjectId == null) {
            return List.of();
        }
        QueryWrapper<CompanyBiResourceAcl> wrapper = new QueryWrapper<>();
        wrapper.eq("subject_type", subjectType.name()).eq("subject_id", subjectId)
                .orderByAsc("resource_type", "resource_id");
        List<CompanyBiResourceAcl> rows = mapper.selectList(wrapper);
        return rows == null ? List.of() : rows;
    }

    public AclWriteResult upsert(CompanySubjectType subjectType, Long subjectId,
                                 CompanyResourceType resourceType, Long resourceId,
                                 CompanyRelationLevel relationLevel, Long operatorUserId) {
        if (subjectType == null || subjectId == null || resourceType == null || resourceId == null || relationLevel == null) {
            return AclWriteResult.REJECTED;
        }
        CompanyBiResourceAcl existing = find(subjectType, subjectId, resourceType, resourceId);
        long now = System.currentTimeMillis();
        if (existing == null) {
            CompanyBiResourceAcl row = new CompanyBiResourceAcl();
            row.setSubjectType(subjectType.name());
            row.setSubjectId(subjectId);
            row.setResourceType(resourceType.name());
            row.setResourceId(resourceId);
            row.setRelationLevel(relationLevel.name());
            row.setCreatedAt(now);
            row.setCreatedBy(operatorUserId);
            row.setUpdatedAt(now);
            row.setUpdatedBy(operatorUserId);
            try {
                mapper.insert(row);
                return AclWriteResult.GRANTED;
            } catch (DuplicateKeyException ignored) {
                CompanyBiResourceAcl raced = find(subjectType, subjectId, resourceType, resourceId);
                if (raced != null && relationLevel.name().equals(raced.getRelationLevel())) {
                    return AclWriteResult.UNCHANGED;
                }
                if (raced != null) {
                    raced.setRelationLevel(relationLevel.name());
                    raced.setUpdatedAt(now);
                    raced.setUpdatedBy(operatorUserId);
                    mapper.updateById(raced);
                    return AclWriteResult.CHANGED;
                }
                return AclWriteResult.UNCHANGED;
            }
        }
        if (relationLevel.name().equals(existing.getRelationLevel())) {
            return AclWriteResult.UNCHANGED;
        }
        existing.setRelationLevel(relationLevel.name());
        existing.setUpdatedAt(now);
        existing.setUpdatedBy(operatorUserId);
        mapper.updateById(existing);
        return AclWriteResult.CHANGED;
    }

    public AclWriteResult revoke(CompanySubjectType subjectType, Long subjectId,
                                 CompanyResourceType resourceType, Long resourceId) {
        CompanyBiResourceAcl existing = find(subjectType, subjectId, resourceType, resourceId);
        if (existing == null) {
            return AclWriteResult.UNCHANGED;
        }
        QueryWrapper<CompanyBiResourceAcl> wrapper = new QueryWrapper<>();
        wrapper.eq("subject_type", subjectType.name())
                .eq("subject_id", subjectId)
                .eq("resource_type", resourceType.name())
                .eq("resource_id", resourceId);
        mapper.delete(wrapper);
        return AclWriteResult.REVOKED;
    }

    public enum AclWriteResult {
        GRANTED,
        CHANGED,
        UNCHANGED,
        REVOKED,
        REJECTED
    }
}
