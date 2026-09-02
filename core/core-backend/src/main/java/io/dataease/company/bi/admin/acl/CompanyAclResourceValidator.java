package io.dataease.company.bi.admin.acl;

import io.dataease.company.bi.acl.CompanyResourceType;
import io.dataease.company.bi.admin.CompanyAdminErrors;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.permission.PermissionDecisionService;
import io.dataease.company.bi.permission.VisualizationHierarchy;
import io.dataease.dataset.dao.auto.entity.CoreDatasetGroup;
import io.dataease.dataset.dao.auto.mapper.CoreDatasetGroupMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyAclResourceValidator {

    private final PermissionDecisionService permissionDecisionService;
    private final CoreDatasetGroupMapper datasetGroupMapper;

    public CompanyAclResourceValidator(PermissionDecisionService permissionDecisionService,
                                       CoreDatasetGroupMapper datasetGroupMapper) {
        this.permissionDecisionService = permissionDecisionService;
        this.datasetGroupMapper = datasetGroupMapper;
    }

    public void requireValid(CompanyResourceType resourceType, Long resourceId) {
        if (resourceType == null || resourceId == null) {
            CompanyAdminErrors.invalid("资源无效");
        }
        switch (resourceType) {
            case DASHBOARD -> requireDashboardLeaf(resourceId);
            case DASHBOARD_FOLDER -> requireFolder(resourceId);
            case DATASET -> requireDataset(resourceId);
        }
    }

    private void requireDashboardLeaf(Long resourceId) {
        VisualizationHierarchy hierarchy = permissionDecisionService.hierarchy();
        if (!hierarchy.isDashboardLeaf(resourceId)) {
            CompanyAdminErrors.invalid("资源不是有效仪表板");
        }
    }

    private void requireFolder(Long resourceId) {
        VisualizationHierarchy hierarchy = permissionDecisionService.hierarchy();
        if (!hierarchy.isFolder(resourceId)) {
            CompanyAdminErrors.invalid("资源不是有效文件夹");
        }
    }

    private void requireDataset(Long resourceId) {
        CoreDatasetGroup row = datasetGroupMapper.selectById(resourceId);
        if (row == null || !"dataset".equals(row.getNodeType())) {
            CompanyAdminErrors.invalid("资源不是有效数据集");
        }
    }
}
