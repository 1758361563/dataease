package io.dataease.company.bi.permission;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.dataease.chart.dao.auto.entity.CoreChartView;
import io.dataease.chart.dao.auto.mapper.CoreChartViewMapper;
import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.visualization.dao.auto.entity.SnapshotCoreChartView;
import io.dataease.visualization.dao.auto.mapper.SnapshotCoreChartViewMapper;

import java.lang.reflect.Proxy;

/** Isolated authorization regression tests: no database, HTTP server or user data. */
public class CompanyDashboardDataGuardTest {
    private static int checks;

    public static void main(String[] args) {
        CoreChartView published = new CoreChartView();
        published.setId(10L); published.setSceneId(100L); published.setTableId(200L);
        SnapshotCoreChartView draft = new SnapshotCoreChartView();
        draft.setId(10L); draft.setSceneId(100L); draft.setTableId(300L);
        var permissions = new CompanyDashboardGuard(null) {
            @Override public void requireView(Long scene) {
                if (!Long.valueOf(100).equals(scene)) throw new SecurityException("dashboard denied");
            }
            @Override public void requireEdit(Long scene) { throw new SecurityException("read-only viewer"); }
        };
        var guard = new CompanyDashboardDataGuard(mapper(CoreChartViewMapper.class, published, 200L),
                mapper(SnapshotCoreChartViewMapper.class, draft, 300L), permissions);
        ChartViewDTO request = new ChartViewDTO();
        request.setId(10L); request.setSceneId(100L); request.setTableId(200L); request.setResourceTable("core");
        check(guard.canReadChartDataset(request), "published dashboard does not require dataset ACL");
        request.setSceneId(null);
        check(guard.canReadChartDataset(request), "scene resolved from persisted chart");
        request.setSceneId(999L);
        check(!guard.canReadChartDataset(request), "forged scene rejected");
        request.setSceneId(100L); request.setTableId(999L);
        check(!guard.canReadChartDataset(request), "unrelated dataset rejected");
        request.setTableId(200L); request.setId(999L);
        check(!guard.canReadChartDataset(request), "unknown chart rejected");
        request.setId(10L); request.setResourceTable("snapshot"); request.setTableId(300L);
        denied(() -> guard.canReadChartDataset(request), "viewer cannot read draft");
        request.setResourceTable("unexpected");
        check(!guard.canReadChartDataset(request), "unknown version rejected");
        check(guard.canReadFilterDataset(200L, 100L, "core"), "filter reads referenced dataset");
        check(!guard.canReadFilterDataset(999L, 100L, "core"), "filter cannot read unrelated dataset");
        check(!guard.canReadFilterDataset(200L, null, "core"), "direct dataset request has no inherited permission");
        check(!guard.canReadFilterDataset(200L, 100L, "unexpected"), "filter version validated");
        denied(() -> guard.canReadFilterDataset(300L, 100L, "snapshot"), "filter draft needs edit");
        published.setSceneId(999L); request.setSceneId(999L); request.setTableId(200L); request.setResourceTable("core");
        denied(() -> guard.canReadChartDataset(request), "another dashboard permission still enforced");
        System.out.println("Dashboard data authorization: " + checks + " checks passed");
    }

    @SuppressWarnings("unchecked")
    private static <T> T mapper(Class<T> type, Object chart, Long dataset) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("selectById")) return Long.valueOf(10).equals(args[0]) ? chart : null;
            if (method.getName().equals("selectCount")) {
                QueryWrapper<?> query = (QueryWrapper<?>) args[0];
                String sql = query.getSqlSegment();
                check(sql.contains("scene_id") && sql.contains("table_id"), "relationship query scopes both IDs");
                return query.getParamNameValuePairs().containsValue(100L)
                        && query.getParamNameValuePairs().containsValue(dataset) ? 1L : 0L;
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }

    private static void denied(Runnable action, String message) {
        try { action.run(); } catch (SecurityException expected) { checks++; return; }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
