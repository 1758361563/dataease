package io.dataease.company.bi.permission;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.dataease.company.bi.acl.CompanyAclRepository;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.dataset.dao.auto.entity.CoreDatasetGroup;
import io.dataease.dataset.dao.auto.mapper.CoreDatasetGroupMapper;
import io.dataease.utils.LogUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyDatasetAclBootstrapper {

    private final CoreDatasetGroupMapper datasetGroupMapper;
    private final CompanyAclRepository aclRepository;

    public CompanyDatasetAclBootstrapper(CoreDatasetGroupMapper datasetGroupMapper,
                                         CompanyAclRepository aclRepository) {
        this.datasetGroupMapper = datasetGroupMapper;
        this.aclRepository = aclRepository;
    }

    public List<Long> listExistingDatasetIds() {
        QueryWrapper<CoreDatasetGroup> wrapper = new QueryWrapper<>();
        wrapper.eq("node_type", "dataset").select("id");
        List<CoreDatasetGroup> rows = datasetGroupMapper.selectList(wrapper);
        List<Long> ids = new ArrayList<>();
        if (rows == null) {
            return ids;
        }
        for (CoreDatasetGroup row : rows) {
            if (row.getId() != null) {
                ids.add(row.getId());
            }
        }
        return ids;
    }

    public int seedBootstrapAdminExistingDatasets() {
        List<Long> datasetIds = listExistingDatasetIds();
        int inserted = seedUserDatasetRead(CompanyBiUserService.BOOTSTRAP_ADMIN_ID, datasetIds);
        LogUtil.info("Seeded bootstrap admin Dataset READ ACL: inserted=" + inserted
                + " existingDatasets=" + datasetIds.size());
        return inserted;
    }

    public int seedUserDatasetRead(Long userId, List<Long> datasetIds) {
        if (userId == null || datasetIds == null || datasetIds.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (Long datasetId : datasetIds) {
            if (aclRepository.insertIfAbsentUserDatasetRead(userId, datasetId, userId)) {
                inserted++;
            }
        }
        return inserted;
    }
}
