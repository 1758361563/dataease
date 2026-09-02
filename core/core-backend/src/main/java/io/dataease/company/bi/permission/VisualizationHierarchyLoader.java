package io.dataease.company.bi.permission;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.visualization.dao.auto.entity.DataVisualizationInfo;
import io.dataease.visualization.dao.auto.mapper.DataVisualizationInfoMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class VisualizationHierarchyLoader implements VisualizationHierarchySource {

    private final DataVisualizationInfoMapper visualizationInfoMapper;

    public VisualizationHierarchyLoader(DataVisualizationInfoMapper visualizationInfoMapper) {
        this.visualizationInfoMapper = visualizationInfoMapper;
    }

    @Override
    public VisualizationHierarchy load() {
        QueryWrapper<DataVisualizationInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("delete_flag", false)
                .ne("pid", "-1")
                .select("id", "pid", "node_type", "type");
        List<DataVisualizationInfo> rows = visualizationInfoMapper.selectList(wrapper);
        List<VisualizationHierarchy.Node> nodes = new ArrayList<>();
        if (rows == null) {
            return VisualizationHierarchy.empty();
        }
        for (DataVisualizationInfo row : rows) {
            if (row == null || row.getId() == null) {
                continue;
            }
            boolean folder = "folder".equals(row.getNodeType());
            boolean leaf = "leaf".equals(row.getNodeType());
            nodes.add(new VisualizationHierarchy.Node(row.getId(), row.getPid(), folder, leaf));
        }
        return new VisualizationHierarchy(nodes);
    }
}
