package io.dataease.company.bi.permission;

import io.dataease.api.chart.request.ChartExcelRequest;
import io.dataease.chart.dao.auto.entity.CoreChartView;
import io.dataease.chart.dao.auto.mapper.CoreChartViewMapper;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.dataset.dao.auto.entity.CoreDatasetTableField;
import io.dataease.dataset.dao.auto.mapper.CoreDatasetTableFieldMapper;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyDashboardExportGuard {

    private final CoreChartViewMapper coreChartViewMapper;
    private final CoreDatasetTableFieldMapper datasetTableFieldMapper;
    private final CompanyDashboardGuard dashboardGuard;

    public CompanyDashboardExportGuard(CoreChartViewMapper coreChartViewMapper,
                                       CoreDatasetTableFieldMapper datasetTableFieldMapper,
                                       CompanyDashboardGuard dashboardGuard) {
        this.coreChartViewMapper = coreChartViewMapper;
        this.datasetTableFieldMapper = datasetTableFieldMapper;
        this.dashboardGuard = dashboardGuard;
    }

    public void requireViewByScene(Long dashboardId) {
        dashboardGuard.requireView(dashboardId);
    }

    public void requireEditByScene(Long dashboardId) {
        dashboardGuard.requireEdit(dashboardId);
    }

    public void requireSavedChartView(Long chartId) {
        dashboardGuard.requireView(requireSceneId(chartId));
    }

    public void requireSavedChartEdit(Long chartId) {
        dashboardGuard.requireEdit(requireSceneId(chartId));
    }

    public void requireSavedChartFieldEdit(Long fieldId) {
        if (fieldId == null) {
            deny();
        }
        CoreDatasetTableField field = loadField(fieldId);
        if (field == null || field.getChartId() == null) {
            deny();
        }
        requireSavedChartEdit(field.getChartId());
    }

    public void requireChartExport(ChartExcelRequest request) {
        Long dashboardId = resolveDashboardId(request);
        CompanyExportKind kind = kindOf(request);
        dashboardGuard.requireExport(dashboardId, kind);
    }

    public Long resolveDashboardId(ChartExcelRequest request) {
        if (request == null) {
            deny();
        }
        Long fromView = null;
        if (StringUtils.isNotBlank(request.getViewId())) {
            CoreChartView chart = loadChart(Long.valueOf(request.getViewId()));
            if (chart == null || chart.getSceneId() == null) {
                deny();
            }
            fromView = chart.getSceneId();
        }
        Long fromDv = parseLong(request.getDvId());
        if (fromView != null && fromDv != null && !fromView.equals(fromDv)) {
            deny();
        }
        if (fromView != null) {
            return fromView;
        }
        if (fromDv != null) {
            return fromDv;
        }
        deny();
        return null;
    }

    public static CompanyExportKind kindOf(ChartExcelRequest request) {
        if (request != null && "dataset".equals(request.getDownloadType())) {
            return CompanyExportKind.DETAIL;
        }
        return CompanyExportKind.EXCEL_VIEW;
    }

    private Long requireSceneId(Long chartId) {
        if (chartId == null) {
            deny();
        }
        CoreChartView chart = loadChart(chartId);
        if (chart == null || chart.getSceneId() == null) {
            deny();
        }
        return chart.getSceneId();
    }

    protected CoreChartView loadChart(Long chartId) {
        return coreChartViewMapper.selectById(chartId);
    }

    protected CoreDatasetTableField loadField(Long fieldId) {
        return datasetTableFieldMapper.selectById(fieldId);
    }

    private static Long parseLong(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void deny() {
        DEException.throwException(ResultCode.PERMISSION_NO_ACCESS.code(), ResultCode.PERMISSION_NO_ACCESS.message());
    }
}
