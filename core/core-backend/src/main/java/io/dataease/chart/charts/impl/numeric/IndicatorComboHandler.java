package io.dataease.chart.charts.impl.numeric;

import io.dataease.chart.charts.impl.YoyChartHandler;
import io.dataease.chart.utils.ChartDataBuild;
import io.dataease.extensions.view.dto.AxisFormatResult;
import io.dataease.extensions.view.dto.ChartAxis;
import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.extensions.view.dto.ChartViewFieldDTO;
import io.dataease.extensions.view.dto.CustomFilterResult;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class IndicatorComboHandler extends YoyChartHandler {
    @Getter
    private final String render = "custom";
    @Getter
    private final String type = "indicator-combo";

    @Override
    public AxisFormatResult formatAxis(ChartViewDTO view) {
        Map<ChartAxis, List<ChartViewFieldDTO>> axisMap = new HashMap<>();
        axisMap.put(ChartAxis.xAxis, new ArrayList<>(view.getXAxis()));
        axisMap.put(ChartAxis.yAxis, new ArrayList<>(view.getYAxis()));
        return new AxisFormatResult(axisMap, new HashMap<>());
    }

    @Override
    public Map<String, Object> buildNormalResult(ChartViewDTO view, AxisFormatResult formatResult,
                                                  CustomFilterResult filterResult, List<String[]> data) {
        boolean isDrill = filterResult.getFilterList().stream().anyMatch(filter -> filter.getFilterType() == 1);
        List<ChartViewFieldDTO> xAxis = formatResult.getAxisMap().get(ChartAxis.xAxis);
        List<ChartViewFieldDTO> yAxis = formatResult.getAxisMap().get(ChartAxis.yAxis);
        return ChartDataBuild.transNormalChartData(xAxis, yAxis, view, data, isDrill);
    }
}
