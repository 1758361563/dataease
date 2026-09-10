package io.dataease.chart.charts.impl.numeric;

import io.dataease.extensions.view.dto.AxisFormatResult;
import io.dataease.extensions.view.dto.ChartAxis;
import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.extensions.view.dto.ChartViewFieldDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class IndicatorComboHandlerTest {

    @Test
    void keepsOneBreakdownDimensionForGroupedAggregation() {
        ChartViewFieldDTO breakdown = new ChartViewFieldDTO();
        breakdown.setId(101L);
        ChartViewFieldDTO metric = new ChartViewFieldDTO();
        metric.setId(202L);
        ChartViewDTO view = new ChartViewDTO();
        view.setXAxis(List.of(breakdown));
        view.setYAxis(List.of(metric));

        IndicatorComboHandler handler = new IndicatorComboHandler();
        AxisFormatResult result = handler.formatAxis(view);

        assertEquals(List.of(breakdown), result.getAxisMap().get(ChartAxis.xAxis));
        assertEquals(List.of(metric), result.getAxisMap().get(ChartAxis.yAxis));
        assertNotSame(view.getXAxis(), result.getAxisMap().get(ChartAxis.xAxis));
        assertNotSame(view.getYAxis(), result.getAxisMap().get(ChartAxis.yAxis));
        assertEquals("custom", handler.getRender());
        assertEquals("indicator-combo", handler.getType());
    }
}
