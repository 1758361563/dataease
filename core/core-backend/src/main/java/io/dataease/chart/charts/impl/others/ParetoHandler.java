package io.dataease.chart.charts.impl.others;

import io.dataease.chart.charts.impl.ExtQuotaChartHandler;
import io.dataease.extensions.view.dto.*;
import io.dataease.extensions.datasource.model.SQLMeta;
import io.dataease.extensions.datasource.provider.Provider;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;

/** Pareto percentages require the complete filtered aggregate, never a Top-N sample. */
@Component
public class ParetoHandler extends ExtQuotaChartHandler {
    public static final int MAX_CATEGORIES = 5000;
    @Override public String getType() { return "pareto"; }

    @Override public AxisFormatResult formatAxis(ChartViewDTO view) {
        if (view.getXAxis().size()!=1 || view.getYAxis().size()!=1)
            throw new IllegalArgumentException("帕累托图需要一个维度和一个指标");
        var measure = view.getYAxis().getFirst();
        if (measure.getCompareCalc()!=null && measure.getCompareCalc().getType()!=null
                && !Set.of("", "none").contains(measure.getCompareCalc().getType()))
            throw new IllegalArgumentException("帕累托图请使用原始聚合指标，累计占比由图表自动计算");
        return super.formatAxis(view);
    }

    @Override public <T extends ChartCalcDataResult> T calcChartResult(ChartViewDTO view, AxisFormatResult axes,
            CustomFilterResult filters, Map<String,Object> sqlMap, SQLMeta meta, Provider provider) {
        String mode = view.getResultMode(); Integer count = view.getResultCount();
        try {
            view.setResultMode("custom");
            view.setResultCount(MAX_CATEGORIES + 1);
            return super.calcChartResult(view, axes, filters, sqlMap, meta, provider);
        } finally {
            view.setResultMode(mode); view.setResultCount(count);
        }
    }

    static List<String[]> ordered(List<String[]> data, int metricIndex) {
        if (data.size()>MAX_CATEGORIES) throw new IllegalArgumentException("帕累托图类别超过 5000，请缩小筛选范围后重试");
        for (var row : data) {
            if (number(row[metricIndex]).signum()<0)
                throw new IllegalArgumentException("帕累托图指标包含负值，请调整指标或筛选条件");
        }
        var result = new ArrayList<>(data);
        result.sort(Comparator.<String[],BigDecimal>comparing(row -> number(row[metricIndex])).reversed()
                .thenComparing(row -> Objects.toString(row[0], "")));
        return result;
    }
    private static BigDecimal number(String value) {
        if (value==null || value.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(value); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("帕累托图指标包含无效数值"); }
    }
    @Override public Map<String,Object> buildResult(ChartViewDTO view, AxisFormatResult axes, CustomFilterResult filters, List<String[]> data) {
        if (axes.getAxisMap().get(ChartAxis.xAxis).size()!=1 || axes.getAxisMap().get(ChartAxis.yAxis).isEmpty())
            throw new IllegalArgumentException("帕累托图维度或指标不可用");
        var sorted = ordered(data, 1);
        return super.buildResult(view, axes, filters, data);
    }
}
