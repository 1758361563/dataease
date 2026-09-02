package io.dataease.company.bi.admin.acl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.dataease.company.bi.acl.CompanyResourceType;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.permission.VisualizationHierarchy;
import io.dataease.dataset.dao.auto.entity.CoreDatasetGroup;
import io.dataease.dataset.dao.auto.mapper.CoreDatasetGroupMapper;
import io.dataease.visualization.dao.auto.entity.DataVisualizationInfo;
import io.dataease.visualization.dao.auto.mapper.DataVisualizationInfoMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyAdminCatalogService {

    private final DataVisualizationInfoMapper visualizationInfoMapper;
    private final CoreDatasetGroupMapper datasetGroupMapper;

    public CompanyAdminCatalogService(DataVisualizationInfoMapper visualizationInfoMapper,
                                      CoreDatasetGroupMapper datasetGroupMapper) {
        this.visualizationInfoMapper = visualizationInfoMapper;
        this.datasetGroupMapper = datasetGroupMapper;
    }

    public CompanyAdminCatalogVO catalog() {
        CompanyAdminCatalogVO vo = new CompanyAdminCatalogVO();
        vo.setDashboards(visualizationTree());
        vo.setDatasets(datasetTree());
        return vo;
    }

    public Map<Long, String> names(CompanyResourceType resourceType, List<Long> ids) {
        Map<Long, String> names = new HashMap<>();
        if (resourceType == null || ids == null || ids.isEmpty()) {
            return names;
        }
        if (resourceType == CompanyResourceType.DATASET) {
            List<CoreDatasetGroup> rows = datasetGroupMapper.selectBatchIds(ids);
            if (rows != null) {
                for (CoreDatasetGroup row : rows) {
                    if (row != null) {
                        names.put(row.getId(), row.getName());
                    }
                }
            }
            return names;
        }
        QueryWrapper<DataVisualizationInfo> wrapper = new QueryWrapper<>();
        wrapper.in("id", ids.stream().map(io.dataease.visualization.jdbc.VisualizationIds::exact).toList()).select("id", "name");
        List<DataVisualizationInfo> rows = visualizationInfoMapper.selectList(wrapper);
        if (rows != null) {
            for (DataVisualizationInfo row : rows) {
                if (row != null) {
                    names.put(row.getId(), row.getName());
                }
            }
        }
        return names;
    }

    private List<CompanyCatalogNodeVO> visualizationTree() {
        QueryWrapper<DataVisualizationInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("delete_flag", false)
                .ne("pid", "-1")
                .select("id", "name", "pid", "node_type", "type");
        List<DataVisualizationInfo> rows = visualizationInfoMapper.selectList(wrapper);
        List<CompanyCatalogNodeVO> nodes = new ArrayList<>();
        if (rows == null) {
            return nodes;
        }
        for (DataVisualizationInfo row : rows) {
            if (row == null || row.getId() == null) {
                continue;
            }
            CompanyCatalogNodeVO node = new CompanyCatalogNodeVO();
            node.setId(row.getId());
            node.setName(row.getName());
            node.setPid(VisualizationHierarchy.isRootPid(row.getPid()) ? 0L : row.getPid());
            boolean folder = "folder".equals(row.getNodeType());
            node.setType(folder ? "DASHBOARD_FOLDER" : "DASHBOARD");
            node.setLeaf(!folder && "leaf".equals(row.getNodeType()));
            nodes.add(node);
        }
        return nest(nodes);
    }

    private List<CompanyCatalogNodeVO> datasetTree() {
        QueryWrapper<CoreDatasetGroup> wrapper = new QueryWrapper<>();
        wrapper.select("id", "name", "pid", "node_type", "type");
        List<CoreDatasetGroup> rows = datasetGroupMapper.selectList(wrapper);
        List<CompanyCatalogNodeVO> nodes = new ArrayList<>();
        if (rows == null) {
            return nodes;
        }
        for (CoreDatasetGroup row : rows) {
            if (row == null || row.getId() == null) {
                continue;
            }
            CompanyCatalogNodeVO node = new CompanyCatalogNodeVO();
            node.setId(row.getId());
            node.setName(row.getName());
            node.setPid(row.getPid() == null || row.getPid() == 0L ? 0L : row.getPid());
            boolean dataset = "dataset".equals(row.getNodeType());
            node.setType(dataset ? "DATASET" : "DATASET_FOLDER");
            node.setLeaf(dataset);
            nodes.add(node);
        }
        return nest(nodes);
    }

    static List<CompanyCatalogNodeVO> nest(List<CompanyCatalogNodeVO> flat) {
        Map<Long, CompanyCatalogNodeVO> byId = new HashMap<>();
        for (CompanyCatalogNodeVO node : flat) {
            byId.put(node.getId(), node);
        }
        List<CompanyCatalogNodeVO> roots = new ArrayList<>();
        for (CompanyCatalogNodeVO node : flat) {
            Long pid = node.getPid();
            if (pid == null || pid == 0L || !byId.containsKey(pid)) {
                roots.add(node);
                continue;
            }
            byId.get(pid).getChildren().add(node);
        }
        return roots;
    }
}
