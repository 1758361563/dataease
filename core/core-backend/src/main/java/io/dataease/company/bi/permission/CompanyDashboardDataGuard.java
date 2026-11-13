package io.dataease.company.bi.permission;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.dataease.chart.dao.auto.entity.CoreChartView;
import io.dataease.chart.dao.auto.mapper.CoreChartViewMapper;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.visualization.dao.auto.entity.SnapshotCoreChartView;
import io.dataease.visualization.dao.auto.mapper.SnapshotCoreChartViewMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Dashboard runtime access is scoped to persisted chart/dataset relationships, not dataset management ACLs. */
@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyDashboardDataGuard {
    private final CoreChartViewMapper charts;
    private final SnapshotCoreChartViewMapper snapshots;
    private final CompanyDashboardGuard dashboards;

    public CompanyDashboardDataGuard(CoreChartViewMapper charts, SnapshotCoreChartViewMapper snapshots,
                                     CompanyDashboardGuard dashboards) {
        this.charts = charts;
        this.snapshots = snapshots;
        this.dashboards = dashboards;
    }

    public boolean canReadChartDataset(ChartViewDTO request) {
        if (request == null || request.getId() == null || request.getTableId() == null) return false;
        Long scene;
        Long dataset;
        if ("snapshot".equals(request.getResourceTable())) {
            var saved = snapshots.selectById(request.getId());
            if (saved == null) return false;
            scene = saved.getSceneId();
            dataset = saved.getTableId();
        } else if ("core".equals(request.getResourceTable())) {
            var saved = charts.selectById(request.getId());
            if (saved == null) return false;
            scene = saved.getSceneId();
            dataset = saved.getTableId();
        } else {
            return false;
        }
        if (scene == null || !Objects.equals(dataset, request.getTableId())
                || (request.getSceneId() != null && !Objects.equals(scene, request.getSceneId()))) return false;
        requireScope(scene, request.getResourceTable());
        return true;
    }

    public boolean canReadFilterDataset(Long dataset, Long scene, String resourceTable) {
        if (dataset == null || scene == null) return false;
        boolean referenced;
        if ("core".equals(resourceTable)) {
            referenced = charts.selectCount(new QueryWrapper<CoreChartView>()
                    .eq("scene_id", scene).eq("table_id", dataset)) > 0;
        } else if ("snapshot".equals(resourceTable)) {
            referenced = snapshots.selectCount(new QueryWrapper<SnapshotCoreChartView>()
                    .eq("scene_id", scene).eq("table_id", dataset)) > 0;
        } else {
            return false;
        }
        if (!referenced) return false;
        requireScope(scene, resourceTable);
        return true;
    }

    private void requireScope(Long scene, String resourceTable) {
        if ("snapshot".equals(resourceTable)) dashboards.requireEdit(scene);
        else dashboards.requireView(scene);
    }
}
