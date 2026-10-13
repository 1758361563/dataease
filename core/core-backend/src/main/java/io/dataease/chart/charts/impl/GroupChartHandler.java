package io.dataease.chart.charts.impl;


import io.dataease.extensions.view.dto.AxisFormatResult;
import io.dataease.extensions.view.dto.ChartAxis;
import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.extensions.view.dto.ChartViewFieldDTO;

import java.util.ArrayList;

public class GroupChartHandler extends YoyChartHandler {
    @Override
    public AxisFormatResult formatAxis(ChartViewDTO view) {
        if ("table-pivot".equals(view.getType())) {
            var dimensions = new ArrayList<ChartViewFieldDTO>(view.getXAxis());
            dimensions.addAll(view.getXAxisExt());
            var counts = new java.util.HashMap<String, Integer>();
            dimensions.forEach(field -> counts.merge(field.resultKey(), 1, Integer::sum));
            var axes = java.util.Map.of("xAxis", view.getXAxis(), "xAxisExt", view.getXAxisExt());
            axes.forEach((axis, fields) -> {
                for (int i = 0; i < fields.size(); i++) {
                    var field = fields.get(i);
                    if (counts.getOrDefault(field.resultKey(), 0) > 1)
                        field.setFieldInstanceId("pd_" + axis + "_" + i + "_" + field.getId());
                }
            });
        }
        var result = super.formatAxis(view);
        var xAxis = new ArrayList<ChartViewFieldDTO>(view.getXAxis());
        xAxis.addAll(view.getXAxisExt());
        result.getAxisMap().put(ChartAxis.xAxis, xAxis);
        return result;
    }
}
