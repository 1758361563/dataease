package io.dataease.visualization.calculation;

import io.dataease.api.visualization.dto.DashboardCalculationConfig;
import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import io.dataease.utils.IDUtils;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Normalizes names to stable IDs and validates dataset/visualization boundaries. */
public class DashboardCalculationValidator {
    private static final Pattern REFERENCE = Pattern.compile("\\[([^\\[\\]]+)]");

    public DashboardCalculationConfig validate(DashboardCalculationConfig config,
            Map<Long, List<DatasetTableFieldDTO>> publicFields) {
        if (config == null || config.getVersion() != 1) fail("不支持的仪表板计算配置版本");
        ZoneId.of(config.getTimezone());
        if (config.getParameters() == null || config.getDatasetFields() == null) fail("参数和共享字段列表不能为空");
        Map<Long, DashboardCalculationConfig.Parameter> parameters = new LinkedHashMap<>();
        Map<String, Long> parameterNames = new HashMap<>();
        Set<Long> ids = new HashSet<>();
        Set<Long> publicIds = new HashSet<>();
        publicFields.values().forEach(fields -> fields.forEach(f -> publicIds.add(f.getId())));
        for (var parameter : config.getParameters()) {
            if (parameter == null) fail("参数不能为空");
            if (parameter.getId() == null) parameter.setId(IDUtils.snowID());
            checkId(parameter.getId(), ids, publicIds);
            parameter.setName(name(parameter.getName()));
            if (parameterNames.put(parameter.getName(), parameter.getId()) != null) fail("参数名称重复：" + parameter.getName());
            if (!"DATE".equals(parameter.getType())) fail("首版仅支持DATE参数：" + parameter.getName());
            parameters.put(parameter.getId(), parameter);
        }
        new DashboardParameterResolver().defaults(config, Instant.parse("2026-10-10T00:00:00Z"));
        Map<Long, DashboardCalculationConfig.SharedField> shared = new LinkedHashMap<>();
        Map<Long, Map<String, Long>> names = new HashMap<>();
        publicFields.forEach((dataset, fields) -> {
            Map<String, Long> map = new HashMap<>();
            fields.forEach(field -> map.put(field.getName(), field.getId()));
            names.put(dataset, map);
        });
        for (var field : config.getDatasetFields()) {
            if (field == null) fail("共享字段不能为空");
            if (field.getId() == null) field.setId(IDUtils.snowID());
            checkId(field.getId(), ids, publicIds);
            field.setName(name(field.getName()));
            if (!publicFields.containsKey(field.getDatasetGroupId())) fail("共享字段的数据集不存在或不可访问：" + field.getName());
            if (!Set.of("d", "q").contains(field.getGroupType())) fail("字段角色必须为d或q");
            if (field.getDeType() == null || !Set.of(0, 1, 2, 3, 4, 5).contains(field.getDeType())) fail("不支持的字段类型");
            if (names.get(field.getDatasetGroupId()).put(field.getName(), field.getId()) != null) fail("字段名称重复：" + field.getName());
            if (field.getExpression() == null || field.getExpression().isBlank()) fail("计算公式不能为空：" + field.getName());
            shared.put(field.getId(), field);
        }
        Map<Long, Set<Long>> dependencies = new LinkedHashMap<>();
        for (var field : shared.values()) {
            Set<Long> allowed = new HashSet<>(names.get(field.getDatasetGroupId()).values());
            Set<Long> deps = new LinkedHashSet<>();
            var matcher = REFERENCE.matcher(field.getExpression());
            StringBuilder normalized = new StringBuilder();
            while (matcher.find()) {
                if (insideLiteral(field.getExpression(), matcher.start())) continue;
                String ref = matcher.group(1);
                String replacement;
                if (ref.startsWith("DYNAMIC_PARAMS.") || ref.startsWith("DVP.")) {
                    Long id = ref.startsWith("DVP.") ? parseId(ref.substring(4)) : parameterNames.get(ref.substring(15));
                    if (id == null || !parameters.containsKey(id)) fail("参数引用不存在：" + ref);
                    replacement = "[DVP." + id + "]";
                } else {
                    Long id = ref.matches("[0-9]+") ? parseId(ref) : names.get(field.getDatasetGroupId()).get(ref);
                    if (id == null || !allowed.contains(id)) fail("字段不属于当前数据集/仪表板：" + ref);
                    if (shared.containsKey(id)) deps.add(id);
                    replacement = "[" + id + "]";
                }
                matcher.appendReplacement(normalized, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(normalized);
            field.setExpression(normalized.toString());
            dependencies.put(field.getId(), deps);
        }
        Set<Long> complete = new HashSet<>();
        for (Long id : shared.keySet()) visit(id, dependencies, complete, new HashSet<>());
        return config;
    }

    private void visit(Long id, Map<Long, Set<Long>> graph, Set<Long> complete, Set<Long> active) {
        if (complete.contains(id)) return;
        if (!active.add(id)) fail("共享计算字段存在循环引用：" + id);
        for (Long dependency : graph.getOrDefault(id, Set.of())) visit(dependency, graph, complete, active);
        active.remove(id);
        complete.add(id);
    }

    private static String name(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 100 || Pattern.compile("[\\[\\]\\r\\n]").matcher(value).find()) fail("名称须为1~100个字符且不能包含方括号或换行");
        return value.trim();
    }

    private static Long parseId(String value) {
        try { return Long.valueOf(value); } catch (NumberFormatException e) { fail("非法引用ID：" + value); return null; }
    }

    private static void checkId(Long id, Set<Long> ids, Set<Long> publicIds) {
        if (id <= 0 || !ids.add(id) || publicIds.contains(id)) fail("参数/共享字段ID重复或与公共字段冲突：" + id);
    }

    static boolean insideLiteral(String expression, int position) {
        char quote = 0;
        for (int i = 0; i < position; i++) {
            char c = expression.charAt(i);
            if (quote != 0 && c == quote && i + 1 < position && expression.charAt(i + 1) == quote) { i++; continue; }
            if (c == '\'' || c == '"' || c == '`') {
                if (quote == 0) quote = c;
                else if (quote == c) quote = 0;
            }
        }
        return quote != 0;
    }

    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
