package io.dataease.chart.charts.impl.mix;

import io.dataease.api.chart.dto.Series;
import io.dataease.chart.utils.AggregateCategoryOrder;
import io.dataease.extensions.view.dto.*;
import org.springframework.beans.BeanUtils;

import java.math.BigDecimal;
import java.util.*;

/** Single metric placement sorting for mix charts without secondary grouping. */
public final class MixMetricSort {
    private MixMetricSort() {}

    public record Selection(String side, int index, boolean ascending) {}

    public static Selection selection(ChartViewDTO view) {
        if (!view.getType().startsWith("chart-mix") || nonEmpty(view.getXAxisExt())
                || nonEmpty(view.getExtStack()) || nonEmpty(view.getExtBubble())) return null;
        // Legacy configurations with dimension ordering retain dimension priority.
        if (view.getXAxis().stream().anyMatch(f -> active(f.getSort()) || "custom_sort".equals(f.getSort()))) return null;
        for (String side : List.of("left", "right")) {
            var fields = "left".equals(side) ? view.getYAxis() : view.getYAxisExt();
            for (int i = 0; i < fields.size(); i++) {
                if (active(fields.get(i).getSort())) return new Selection(side, i, "asc".equals(fields.get(i).getSort()));
            }
        }
        return null;
    }

    private static boolean nonEmpty(List<?> list) { return list != null && !list.isEmpty(); }
    private static boolean active(String sort) { return "asc".equals(sort) || "desc".equals(sort); }

    public static List<ChartViewFieldDTO> withoutSort(List<ChartViewFieldDTO> fields) {
        return new ArrayList<>(fields.stream().map(field -> {
            var copy = new ChartViewFieldDTO();
            BeanUtils.copyProperties(field, copy);
            copy.setSort("none");
            return copy;
        }).toList());
    }

    public static ChartViewDTO fullResultView(ChartViewDTO view) {
        var copy = new ChartViewDTO();
        BeanUtils.copyProperties(view, copy);
        copy.setYAxis(withoutSort(view.getYAxis()));
        copy.setYAxisExt(withoutSort(view.getYAxisExt()));
        // Both axes must see all aggregated categories; limit is applied once after ranking.
        copy.setResultMode("all");
        return copy;
    }

    private static List<String> key(AxisChartDataAntVDTO row) {
        return row.getDimensionList().stream().map(ChartDimensionDTO::getValue).toList();
    }

    private static int compareKeys(List<String> a, List<String> b) {
        var comparator = Comparator.nullsLast(Comparator.<String>naturalOrder());
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int comparison = comparator.compare(a.get(i), b.get(i));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(a.size(), b.size());
    }

    @SuppressWarnings("unchecked")
    public static void apply(ChartViewDTO view, ChartCalcDataResult result, Selection selection,
                             int leftDimensions, int rightDimensions) {
        var categories = new LinkedHashMap<List<String>, String>();
        var values = new HashMap<List<String>, BigDecimal>();
        for (String side : List.of("left", "right")) {
            var axisResult = (ChartCalcDataResult) result.getData().get(side);
            var series = (List<Series>) axisResult.getData().get("data");
            for (int i = 0; i < series.size(); i++) {
                for (Object datum : series.get(i).getData()) {
                    var row = (AxisChartDataAntVDTO) datum;
                    categories.putIfAbsent(key(row), row.getField());
                    // Axis + position identifies the exact aggregation placement, never physical id alone.
                    if (side.equals(selection.side()) && i == selection.index()) values.put(key(row), row.getValue());
                }
            }
        }
        int limit = "custom".equals(view.getResultMode()) ? view.getResultCount() : Integer.MAX_VALUE;
        var order = AggregateCategoryOrder.order(categories.keySet(), values, selection.ascending(), MixMetricSort::compareKeys, limit);
        var ranks = new HashMap<List<String>, Integer>();
        for (int i = 0; i < order.size(); i++) ranks.put(order.get(i), i);
        for (String side : List.of("left", "right")) {
            var axisResult = (ChartCalcDataResult) result.getData().get(side);
            var series = (List<Series>) axisResult.getData().get("data");
            for (Series item : series) {
                item.setData(item.getData().stream()
                        .filter(row -> ranks.containsKey(key((AxisChartDataAntVDTO) row)))
                        .sorted(Comparator.comparingInt(row -> ranks.get(key((AxisChartDataAntVDTO) row)))).toList());
            }
            int dimensions = "left".equals(side) ? leftDimensions : rightDimensions;
            axisResult.setOriginData(axisResult.getOriginData().stream()
                    .filter(row -> ranks.containsKey(Arrays.asList(row).subList(0, dimensions)))
                    .sorted(Comparator.comparingInt(row -> ranks.get(Arrays.asList(row).subList(0, dimensions)))).toList());
        }
        result.getData().put("categoryOrder", order.stream().map(categories::get).toList());
    }
}
