package io.dataease.chart.server;

import io.dataease.api.chart.ChartViewApi;
import io.dataease.api.chart.vo.ChartBaseVO;
import io.dataease.api.chart.vo.ViewSelectorVO;
import io.dataease.chart.manage.ChartViewManege;
import io.dataease.constant.CommonConstants;
import io.dataease.dataset.utils.DatasetUtils;
import io.dataease.exception.DEException;
import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.extensions.view.dto.ChartViewFieldDTO;
import io.dataease.result.ResultCode;
import io.dataease.company.bi.permission.CompanyDashboardExportGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * @Author Junjun
 */
@RestController
@RequestMapping("chart")
public class ChartViewServer implements ChartViewApi {
    @Resource
    private ChartViewManege chartViewManege;

    @Autowired
    private ObjectProvider<CompanyDashboardExportGuard> companyDashboardExportGuard;

    @Override
    public ChartViewDTO getData(Long id) throws Exception {
        CompanyDashboardExportGuard chartGuard = companyDashboardExportGuard.getIfAvailable();
        if (chartGuard != null) {
            chartGuard.requireSavedChartView(id);
        }
        try {
            return chartViewManege.getChart(id, CommonConstants.RESOURCE_TABLE.CORE);
        } catch (Exception e) {
            DEException.throwException(ResultCode.DATA_IS_WRONG.code(), e.getMessage());
        }
        return null;
    }

    @Override
    public Map<String, List<ChartViewFieldDTO>> listByDQ(Long id, Long chartId, ChartViewDTO dto) {
        Map<String, List<ChartViewFieldDTO>> stringListMap = chartViewManege.listByDQ(id, chartId, dto);
        DatasetUtils.listEncode(stringListMap.get("dimensionList"));
        DatasetUtils.listEncode(stringListMap.get("quotaList"));
        return stringListMap;
    }

    @Override
    public ChartViewDTO save(ChartViewDTO dto) throws Exception {
        CompanyDashboardExportGuard chartGuard = companyDashboardExportGuard.getIfAvailable();
        if (chartGuard != null) {
            if (dto != null && dto.getId() != null) {
                chartGuard.requireSavedChartEdit(dto.getId());
            } else if (dto != null && dto.getSceneId() != null) {
                chartGuard.requireEditByScene(dto.getSceneId());
            } else {
                io.dataease.company.bi.permission.CompanyDashboardGuard.deny();
            }
        }
        return chartViewManege.save(dto);
    }

    @Override
    public String checkSameDataSet(String viewIdSource, String viewIdTarget) {
        CompanyDashboardExportGuard chartGuard = companyDashboardExportGuard.getIfAvailable();
        if (chartGuard != null) {
            chartGuard.requireSavedChartView(parseChartId(viewIdSource));
            chartGuard.requireSavedChartView(parseChartId(viewIdTarget));
        }
        return chartViewManege.checkSameDataSet(viewIdSource, viewIdTarget);
    }

    @Override
    public ChartViewDTO getDetail(Long id, String resourceTable) {
        ChartViewDTO details = chartViewManege.getDetails(id, resourceTable);
        CompanyDashboardExportGuard chartGuard = companyDashboardExportGuard.getIfAvailable();
        if (chartGuard != null) {
            if (details == null || details.getSceneId() == null) {
                io.dataease.company.bi.permission.CompanyDashboardGuard.deny();
            }
            chartGuard.requireViewByScene(details.getSceneId());
        }
        return details;
    }

    @Override
    public List<ViewSelectorVO> viewOption(Long resourceId) {
        CompanyDashboardExportGuard chartGuard = companyDashboardExportGuard.getIfAvailable();
        if (chartGuard != null) {
            chartGuard.requireViewByScene(resourceId);
        }
        return chartViewManege.viewOption(resourceId);
    }

    @Override
    public void copyField(Long id, Long chartId) {
        CompanyDashboardExportGuard chartGuard = companyDashboardExportGuard.getIfAvailable();
        if (chartGuard != null) {
            chartGuard.requireSavedChartEdit(chartId);
        }
        chartViewManege.copyField(id, chartId);
    }

    @Override
    public void deleteField(Long id) {
        CompanyDashboardExportGuard chartGuard = companyDashboardExportGuard.getIfAvailable();
        if (chartGuard != null) {
            chartGuard.requireSavedChartFieldEdit(id);
        }
        chartViewManege.deleteField(id);
    }

    @Override
    public void deleteFieldByChart(Long chartId) {
        CompanyDashboardExportGuard chartGuard = companyDashboardExportGuard.getIfAvailable();
        if (chartGuard != null) {
            chartGuard.requireSavedChartEdit(chartId);
        }
        chartViewManege.deleteFieldByChartId(chartId);
    }

    @Override
    public ChartBaseVO chartBaseInfo(Long id, String resourceTable) {
        CompanyDashboardExportGuard chartGuard = companyDashboardExportGuard.getIfAvailable();
        if (chartGuard != null) {
            chartGuard.requireSavedChartView(id);
        }
        return chartViewManege.chartBaseInfo(id, resourceTable);
    }

    private static Long parseChartId(String value) {
        if (value == null) {
            io.dataease.company.bi.permission.CompanyDashboardGuard.deny();
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            io.dataease.company.bi.permission.CompanyDashboardGuard.deny();
            return null;
        }
    }
}
