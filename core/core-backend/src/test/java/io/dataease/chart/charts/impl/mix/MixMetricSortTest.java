package io.dataease.chart.charts.impl.mix;

import io.dataease.api.chart.dto.Series;
import io.dataease.chart.utils.AggregateCategoryOrder;
import io.dataease.engine.sql.SQLProvider;
import io.dataease.extensions.view.dto.*;
import io.dataease.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MixMetricSortTest {
    private ChartViewFieldDTO field(String instance, String summary, String sort) {
        var field = new ChartViewFieldDTO();
        field.setId(7L);
        field.setFieldInstanceId(instance);
        field.setSummary(summary);
        field.setSort(sort);
        return field;
    }

    private ChartViewDTO view() {
        var view = new ChartViewDTO();
        view.setType("chart-mix");
        view.setXAxis(new ArrayList<>(List.of(field("dimension", null, "none"))));
        view.setYAxis(new ArrayList<>(List.of(field("sum-placement", "sum", "none"))));
        view.setYAxisExt(new ArrayList<>(List.of(field("avg-placement", "avg", "desc"))));
        view.setXAxisExt(new ArrayList<>());
        view.setExtStack(new ArrayList<>());
        view.setExtBubble(new ArrayList<>());
        view.setExtLabel(new ArrayList<>());
        view.setExtTooltip(new ArrayList<>());
        view.setDrillFields(new ArrayList<>());
        view.setResultMode("custom");
        view.setResultCount(2);
        return view;
    }

    private ChartCalcDataResult axis(String[][] rows) {
        var result = new ChartCalcDataResult();
        result.setOriginData(new ArrayList<>(Arrays.asList(rows)));
        var series = new Series();
        series.setData(new ArrayList<>());
        for (var values : rows) {
            var row = new AxisChartDataAntVDTO();
            row.setField(values[0]);
            row.setValue(values[1] == null ? null : new BigDecimal(values[1]));
            var dimension = new ChartDimensionDTO();
            dimension.setValue(values[0]);
            row.setDimensionList(List.of(dimension));
            series.getData().add(row);
        }
        result.setData(new HashMap<>(Map.of("data", List.of(series))));
        return result;
    }

    private ChartCalcDataResult result() {
        var result = new ChartCalcDataResult();
        result.setData(new HashMap<>(Map.of(
                "left", axis(new String[][]{{"A", "900"}, {"B", "300"}, {"C", "600"}}),
                "right", axis(new String[][]{{"C", "10"}, {"A", "20"}, {"B", "90"}, {"D", "100"}}))));
        return result;
    }

    @Test
    void ranksFullAggregationBeforeLimitAndKeepsSparseAxes() {
        var view = view();
        var query = MixMetricSort.fullResultView(view);
        assertFalse(SQLProvider.sqlLimit("SELECT grouped_values", query).contains("LIMIT"));
        assertTrue(SQLProvider.sqlLimit("SELECT grouped_values", view).contains("LIMIT 2"));
        assertEquals("desc", view.getYAxisExt().getFirst().getSort());
        var result = result();
        MixMetricSort.apply(view, result, MixMetricSort.selection(view), 1, 1);
        assertEquals(List.of("D", "B"), result.getData().get("categoryOrder"));
        var left = (ChartCalcDataResult) result.getData().get("left");
        assertEquals(1, left.getOriginData().size());
        assertArrayEquals(new String[]{"B", "300"}, left.getOriginData().getFirst());
        var right = (ChartCalcDataResult) result.getData().get("right");
        assertArrayEquals(new String[]{"D", "100"}, right.getOriginData().getFirst());
        assertArrayEquals(new String[]{"B", "90"}, right.getOriginData().getLast());
    }

    @Test
    void placementAndDirectionSurviveSerializationAndFormatting() {
        var view = JsonUtil.parseObject((String) JsonUtil.toJSONString(view()), ChartViewDTO.class);
        new MixHandler().formatAxis(view);
        var selected = MixMetricSort.selection(view);
        assertEquals("right", selected.side());
        assertEquals("avg", view.getYAxisExt().getFirst().getSummary());
        assertEquals("avg-placement", view.getYAxisExt().getFirst().getFieldInstanceId());
        view.getYAxisExt().getFirst().setSort("asc");
        var result = result();
        MixMetricSort.apply(view, result, MixMetricSort.selection(view), 1, 1);
        assertEquals(List.of("C", "A"), result.getData().get("categoryOrder"));
        view.getYAxisExt().clear();
        assertNull(MixMetricSort.selection(view));
    }

    @Test
    void switchesToLeftAggregationAndHonorsLegacyDimensionPriority() {
        var view = view();
        view.getYAxisExt().getFirst().setSort("none");
        view.getYAxis().getFirst().setSort("desc");
        var result = result();
        MixMetricSort.apply(view, result, MixMetricSort.selection(view), 1, 1);
        assertEquals(List.of("A", "C"), result.getData().get("categoryOrder"));
        view.getXAxis().getFirst().setSort("asc");
        assertNull(MixMetricSort.selection(view));
        view.getXAxis().getFirst().setSort("none");
        view.getYAxis().getFirst().setSort("none");
        assertNull(MixMetricSort.selection(view));
    }

    @Test
    void comparesNumbersNotFormattedStringsWithStableTiesAndNullsLast() {
        var values = new HashMap<String, BigDecimal>();
        values.put("A", new BigDecimal("0.9"));
        values.put("B", new BigDecimal("0.12"));
        values.put("C", BigDecimal.ZERO);
        values.put("D", new BigDecimal("0.12"));
        var categories = List.of("missing", "D", "B", "A", "C");
        assertEquals(List.of("C", "B", "D", "A", "missing"), AggregateCategoryOrder.order(categories, values, true, Comparator.naturalOrder(), 10));
        assertEquals(List.of("A", "B", "D", "C", "missing"), AggregateCategoryOrder.order(categories, values, false, Comparator.naturalOrder(), 10));
    }
}
