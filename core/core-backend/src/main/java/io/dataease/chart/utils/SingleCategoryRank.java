package io.dataease.chart.utils;

import io.dataease.extensions.view.dto.*;
import org.springframework.beans.BeanUtils;
import java.math.BigDecimal;
import java.util.*;

/** Full-result ordering is required when custom order or placement priorities cannot be expressed by the legacy SQL sorter. */
public final class SingleCategoryRank {
    private SingleCategoryRank() {}
    private record Rule(ChartViewFieldDTO field, String axis, int column) {}
    private static final Set<String> TYPES = Set.of("bar", "bar-horizontal", "bar-group", "bar-stack",
            "bar-group-stack", "bar-percentage", "bar-group-horizontal", "bar-stack-horizontal",
            "bar-percentage-horizontal", "line", "area", "area-stack", "pie", "pie-donut");
    private static boolean active(ChartViewFieldDTO field) {
        return field.getSort() != null && Set.of("asc", "desc", "custom_sort").contains(field.getSort());
    }
    private static boolean empty(List<?> fields) { return fields == null || fields.isEmpty(); }

    public static boolean required(ChartViewDTO view, List<ChartViewFieldDTO> x, List<ChartViewFieldDTO> y) {
        if (view.getType() == null || !TYPES.contains(view.getType()) || x.size() != 1
                || !empty(view.getXAxisExt()) || !empty(view.getExtStack()) || !empty(view.getExtBubble())) return false;
        if (y.stream().anyMatch(f -> f.getCompareCalc() != null && f.getCompareCalc().getType() != null
                && !f.getCompareCalc().getType().isEmpty() && !"none".equals(f.getCompareCalc().getType()))) return false;
        var rules = rules(view, x, y);
        return rules.size() > 1 || rules.stream().anyMatch(r -> "custom_sort".equals(r.field().getSort()));
    }

    public static ChartViewDTO fullView(ChartViewDTO view) {
        var query = new ChartViewDTO();
        BeanUtils.copyProperties(view, query);
        query.setResultMode("all");
        return query;
    }

    private static List<Rule> rules(ChartViewDTO view, List<ChartViewFieldDTO> x, List<ChartViewFieldDTO> y) {
        var source = new ArrayList<Rule>();
        for (int i = 0; i < x.size(); i++) if (active(x.get(i))) source.add(new Rule(x.get(i), "xAxis", i));
        // Additional tooltip/label fields are query columns, not chart sort rules.
        int measures = view.getYAxis() == null ? 0 : Math.min(view.getYAxis().size(), y.size());
        for (int i = 0; i < measures; i++) if (active(y.get(i))) source.add(new Rule(y.get(i), "yAxis", x.size() + i));
        var ordered = new ArrayList<Rule>();
        if (view.getSortPriority() != null) for (SortAxis priority : view.getSortPriority()) {
            for (Rule rule : source) if (Objects.equals(priority.getId(), rule.field().getId())
                    && (priority.getAxis() == null || priority.getAxis().equals(rule.axis()))
                    && (priority.getFieldInstanceId() == null || priority.getFieldInstanceId().equals(rule.field().getFieldInstanceId()))
                    && !ordered.contains(rule)) ordered.add(rule);
        }
        for (Rule rule : source) if (!ordered.contains(rule)) ordered.add(rule);
        return ordered;
    }

    public static List<String[]> apply(ChartViewDTO view, List<ChartViewFieldDTO> x, List<ChartViewFieldDTO> y, List<String[]> rows) {
        Comparator<String[]> order = (a, b) -> 0;
        for (Rule rule : rules(view, x, y)) {
            var custom = new HashMap<String, Integer>();
            if (rule.field().getCustomSort() != null) for (String value : rule.field().getCustomSort())
                if (value != null) custom.putIfAbsent(value.toLowerCase(Locale.ROOT), custom.size());
            order = order.thenComparing((a, b) -> compare(a[rule.column()], b[rule.column()], rule, custom));
        }
        order = order.thenComparing(row -> row[0], Comparator.nullsLast(Comparator.naturalOrder()));
        int limit = "custom".equals(view.getResultMode()) && view.getResultCount() != null
                ? Math.max(1, view.getResultCount()) : Integer.MAX_VALUE;
        return rows.stream().sorted(order).limit(limit).toList();
    }

    private static int compare(String a, String b, Rule rule, Map<String, Integer> custom) {
        if (a == null || b == null) return a == b ? 0 : a == null ? 1 : -1;
        if ("custom_sort".equals(rule.field().getSort())) return Integer.compare(
                custom.getOrDefault(a.toLowerCase(Locale.ROOT), Integer.MAX_VALUE),
                custom.getOrDefault(b.toLowerCase(Locale.ROOT), Integer.MAX_VALUE));
        int result;
        boolean numeric = "yAxis".equals(rule.axis()) || Objects.equals(rule.field().getDeType(), 2) || Objects.equals(rule.field().getDeType(), 3);
        if (numeric) {
            try { result = new BigDecimal(a).compareTo(new BigDecimal(b)); }
            catch (NumberFormatException ignored) { result = a.compareTo(b); }
        } else result = a.compareTo(b);
        return "desc".equals(rule.field().getSort()) ? -result : result;
    }
}
