package io.dataease.chart.utils;

import io.dataease.extensions.view.dto.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Percent scopes operate on complete, filtered, additive query results. */
public final class PivotPercentCalculator {
    private PivotPercentCalculator() {}
    public static boolean scoped(ChartFieldCompareDTO calc) {
        return calc != null && "percent".equals(calc.getType()) && calc.getPercentScope() != null
                && !"table".equals(calc.getPercentScope());
    }
    public static boolean required(ChartViewDTO view, List<ChartViewFieldDTO> measures) {
        return "table-pivot".equals(view.getType()) && measures.stream().anyMatch(f -> scoped(f.getCompareCalc()));
    }
    public static List<Integer> indexes(ChartViewDTO view, List<ChartViewFieldDTO> dimensions, ChartFieldCompareDTO calc) {
        List<ChartViewFieldDTO> row = view.getXAxis() == null ? List.of() : view.getXAxis();
        List<ChartViewFieldDTO> col = view.getXAxisExt() == null ? List.of() : view.getXAxisExt();
        var selected = new ArrayList<ChartViewFieldDTO>();
        switch (calc.getPercentScope()) {
            case "row": selected.addAll(row); break;
            case "column": selected.addAll(col); break;
            case "rowGroup":
                int end = -1;
                for (int i = 0; i < row.size(); i++) if (row.get(i).resultKey().equals(calc.getPercentGroup())) end = i;
                if (end < 0 || end >= row.size() - 1) throw new IllegalArgumentException("占比计算分组已失效，请重新选择行分组层级");
                selected.addAll(row.subList(0, end + 1));
                selected.addAll(col);
                break;
            default: throw new IllegalArgumentException("不支持的占比范围：" + calc.getPercentScope());
        }
        var result = new ArrayList<Integer>();
        for (var field : selected) {
            int position = -1;
            for (int i = 0; i < dimensions.size(); i++) if (dimensions.get(i).resultKey().equals(field.resultKey())) position = i;
            if (position < 0) throw new IllegalArgumentException("占比分组字段不在当前查询中，请重新配置");
            result.add(position);
        }
        return result;
    }
    public static void validate(ChartViewDTO view, List<ChartViewFieldDTO> dimensions, List<ChartViewFieldDTO> measures) {
        for (var field : measures) if (scoped(field.getCompareCalc())) {
            if (!Set.of("sum", "count").contains(String.valueOf(field.getSummary()).toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("分组占比目前仅支持求和和计数：" + field.getName());
            indexes(view, dimensions, field.getCompareCalc());
        }
    }
    public static void validateMasked(List<ChartViewFieldDTO> dimensions, List<ChartViewFieldDTO> measures, Set<String> masked) {
        if (masked == null || masked.isEmpty()) return;
        if (dimensions.stream().anyMatch(f -> masked.contains(f.getDataeaseName()))
                || measures.stream().anyMatch(f -> scoped(f.getCompareCalc()) && masked.contains(f.getDataeaseName())))
            throw new IllegalArgumentException("分组占比暂不支持参与计算的字段配置脱敏规则");
    }
    public static List<Map<String, Object>> apply(ChartViewDTO view, List<ChartViewFieldDTO> dimensions,
                                                 List<ChartViewFieldDTO> measures, List<String[]> raw, List<String[]> displayed) {
        var basis = new ArrayList<Map<String, Object>>();
        for (var row : raw) {
            var record = new LinkedHashMap<String, Object>();
            for (int d = 0; d < dimensions.size(); d++) record.put(dimensions.get(d).resultKey(), row[d]);
            for (int m = 0; m < measures.size(); m++) if (scoped(measures.get(m).getCompareCalc()))
                record.put(measures.get(m).resultKey(), row[dimensions.size() + m]);
            basis.add(record);
        }
        for (int m = 0; m < measures.size(); m++) {
            var field = measures.get(m);
            if (!scoped(field.getCompareCalc())) continue;
            var group = indexes(view, dimensions, field.getCompareCalc());
            int valueIndex = dimensions.size() + m;
            var totals = new HashMap<List<String>, BigDecimal>();
            for (var row : raw) {
                var key = new ArrayList<String>();
                for (int index : group) key.add(row[index]);
                if (row[valueIndex] != null && !row[valueIndex].isEmpty())
                    totals.merge(key, new BigDecimal(row[valueIndex]), BigDecimal::add);
            }
            for (int r = 0; r < raw.size(); r++) {
                var row = raw.get(r);
                var key = new ArrayList<String>();
                for (int index : group) key.add(row[index]);
                var total = totals.get(key);
                displayed.get(r)[valueIndex] = row[valueIndex] == null || row[valueIndex].isEmpty() || total == null || total.signum() == 0
                        ? null : new BigDecimal(row[valueIndex]).divide(total, 12, RoundingMode.HALF_UP).toPlainString();
            }
        }
        return basis;
    }
}
