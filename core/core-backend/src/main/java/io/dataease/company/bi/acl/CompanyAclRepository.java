package io.dataease.company.bi.acl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import io.dataease.company.bi.auth.CompanyAuthSupport;

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
}
