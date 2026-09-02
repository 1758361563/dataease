package io.dataease.company.bi.permission;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.dataease.company.bi.auth.CompanyAuthSupport;
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
public class CompanyDashboardFolderSupport {

    private final DataVisualizationInfoMapper visualizationInfoMapper;

    public CompanyDashboardFolderSupport(DataVisualizationInfoMapper visualizationInfoMapper) {
        this.visualizationInfoMapper = visualizationInfoMapper;
    }

    public boolean isDashboardLeaf(Long id) {
        DataVisualizationInfo info = visualizationInfoMapper.selectById(id);
        return info != null
                && "leaf".equals(info.getNodeType())
                && "dashboard".equals(info.getType());
    }

    public boolean isFolder(Long id) {
        DataVisualizationInfo info = visualizationInfoMapper.selectById(id);
        return info != null && "folder".equals(info.getNodeType());
    }

    public List<Long> descendantDashboardLeafIds(Long folderId) {
        QueryWrapper<DataVisualizationInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("delete_flag", false).eq("type", "dashboard").select("id", "pid", "node_type");
        List<DataVisualizationInfo> rows = visualizationInfoMapper.selectList(wrapper);
        Map<Long, List<Long>> children = new HashMap<>();
        if (rows != null) {
            for (DataVisualizationInfo row : rows) {
                if (row.getPid() == null) {
                    continue;
                }
                children.computeIfAbsent(row.getPid(), ignored -> new ArrayList<>()).add(row.getId());
            }
        }
        Map<Long, DataVisualizationInfo> byId = new HashMap<>();
        if (rows != null) {
            for (DataVisualizationInfo row : rows) {
                byId.put(row.getId(), row);
            }
        }
        List<Long> leaves = new ArrayList<>();
        collectLeaves(folderId, children, byId, leaves);
        return leaves;
    }

    private void collectLeaves(Long id, Map<Long, List<Long>> children,
                               Map<Long, DataVisualizationInfo> byId, List<Long> leaves) {
        List<Long> kids = children.get(id);
        if (kids == null) {
            return;
        }
        for (Long kid : kids) {
            DataVisualizationInfo info = byId.get(kid);
            if (info == null) {
                continue;
            }
            if ("leaf".equals(info.getNodeType())) {
                leaves.add(kid);
            } else {
                collectLeaves(kid, children, byId, leaves);
            }
        }
    }
}
