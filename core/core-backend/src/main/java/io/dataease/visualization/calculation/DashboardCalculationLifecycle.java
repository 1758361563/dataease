package io.dataease.visualization.calculation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.dataease.api.visualization.dto.DashboardCalculationConfig;
import io.dataease.dataset.utils.TableUtils;
import io.dataease.utils.IDUtils;
import io.dataease.utils.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Exact reference remapping for copies and imports. Never replace substrings of names or dates. */
public class DashboardCalculationLifecycle {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern REF = Pattern.compile("\\[(DVP\\.)?([0-9]+)]");
    private static final Set<String> IDS = Set.of("id", "fieldId", "sourceField", "targetField", "sourceFieldId", "targetFieldId", "parentId", "viewId", "viewIds", "targetViewId", "sourceViewId", "tableId", "sourceTableId", "datasetGroupId", "datasetTableId", "datasourceId", "chartId", "dvId", "sourceDvId", "targetDvId", "linkageId", "linkJumpId", "linkJumpInfoId", "sourceFieldActiveId", "targetId", "checkedFields");
    public record Remapped(DashboardCalculationConfig config, Map<Long, Long> ids, Map<String, String> aliases) {}

    public static Remapped fresh(String json) {
        var config = DashboardCalculationManage.parse(json);
        Map<Long, Long> ids = new LinkedHashMap<>();
        Map<String, String> aliases = new HashMap<>();
        config.getParameters().forEach(parameter -> ids.put(parameter.getId(), IDUtils.snowID()));
        config.getDatasetFields().forEach(field -> {
            long next = IDUtils.snowID(); ids.put(field.getId(), next);
            aliases.put(TableUtils.fieldNameShort(field.getId().toString()), TableUtils.fieldNameShort(Long.toString(next)));
        });
        config.getParameters().forEach(parameter -> parameter.setId(ids.get(parameter.getId())));
        config.getDatasetFields().forEach(field -> {
            field.setId(ids.get(field.getId())); field.setExpression(expression(field.getExpression(), ids));
        });
        return new Remapped(config, ids, aliases);
    }

    public static DashboardCalculationConfig rebind(String json, Map<Long, Long> datasets, Map<Long, Long> fields) {
        var config = DashboardCalculationManage.parse(json);
        config.getDatasetFields().forEach(field -> {
            field.setDatasetGroupId(datasets.getOrDefault(field.getDatasetGroupId(), field.getDatasetGroupId()));
            field.setExpression(expression(field.getExpression(), fields));
        });
        return config;
    }

    public static String expression(String value, Map<Long, Long> ids) {
        if (value == null) return null;
        Matcher matcher = REF.matcher(value); StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            if (DashboardCalculationValidator.insideLiteral(value, matcher.start())) continue;
            Long next = ids.get(Long.valueOf(matcher.group(2)));
            if (next != null) matcher.appendReplacement(result, Matcher.quoteReplacement("[" + (matcher.group(1) == null ? "" : "DVP.") + next + "]"));
        }
        matcher.appendTail(result); return result.toString();
    }

    public static Set<Long> references(String expression) {
        Set<Long> result = new LinkedHashSet<>();
        if (expression == null) return result;
        if (!expression.contains("[")) {
            try { expression = new String(Base64.getDecoder().decode(expression), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException ignored) { }
        }
        Matcher matcher = REF.matcher(expression);
        while (matcher.find()) {
            if (!DashboardCalculationValidator.insideLiteral(expression, matcher.start())) result.add(Long.valueOf(matcher.group(2)));
        }
        return result;
    }

    public static String json(String value, Map<Long, Long> ids, Map<String, String> aliases, Long dashboardId) {
        if (value == null || value.isBlank()) return value;
        try { return JSON.writeValueAsString(rewrite(JSON.readTree(value), "", ids, aliases, dashboardId)); }
        catch (Exception e) { throw new IllegalArgumentException("计算配置引用重映射失败", e); }
    }

    public static <T> T object(T value, Class<T> type, Remapped remapped, Long dashboardId) {
        return JsonUtil.parseObject(json((String) JsonUtil.toJSONString(value), remapped.ids(), remapped.aliases(), dashboardId), type);
    }

    private static JsonNode rewrite(JsonNode node, String key, Map<Long, Long> ids, Map<String, String> aliases, Long dashboardId) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                if (Set.of("dashboardParameterValues", "dashboardParameterReference", "calculationConfig").contains(name)) return;
                // Grouping stores its source column as a bare ID, not a formula [ID].
                if ("originName".equals(name) && node.path("extField").asInt() == 3) {
                    result.set(name, rewrite(entry.getValue(), "fieldId", ids, aliases, dashboardId));
                    return;
                }
                if ("checkedFieldsMap".equals(key)) {
                    try { name = ids.getOrDefault(Long.valueOf(name), Long.valueOf(name)).toString(); }
                    catch (NumberFormatException ignored) { }
                    result.set(name, rewrite(entry.getValue(), "fieldId", ids, aliases, dashboardId));
                    return;
                }
                result.set(name, rewrite(entry.getValue(), name, ids, aliases, dashboardId));
            }); return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode(); node.forEach(item -> result.add(rewrite(item, key, ids, aliases, dashboardId))); return result;
        }
        if ((node.isTextual() || node.isIntegralNumber()) && IDS.contains(key)) {
            if (node.isTextual() && node.asText().matches("[0-9]+(,[0-9]+)+")) {
                return TextNode.valueOf(String.join(",", Arrays.stream(node.asText().split(",")).map(value -> ids.getOrDefault(Long.valueOf(value), Long.valueOf(value)).toString()).toList()));
            }
            try { Long next = ids.get(Long.valueOf(node.asText())); if (next != null) return node.isTextual() ? TextNode.valueOf(next.toString()) : LongNode.valueOf(next); }
            catch (NumberFormatException ignored) { }
        }
        if ("dashboardId".equals(key) && dashboardId != null) return TextNode.valueOf(dashboardId.toString());
        if (node.isTextual()) {
            String text = node.asText();
            if (!Set.of("originName", "expression").contains(key) && (text.startsWith("[") || text.startsWith("{"))) {
                try { return TextNode.valueOf(JSON.writeValueAsString(rewrite(JSON.readTree(text), key, ids, aliases, dashboardId))); }
                catch (Exception ignored) { }
            }
            if ("originName".equals(key) || "expression".equals(key)) {
                String replaced = expression(text, ids);
                if (!replaced.equals(text)) return TextNode.valueOf(replaced);
                if ("originName".equals(key)) {
                    try {
                        String decoded = new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
                        String mapped = expression(decoded, ids);
                        if (!mapped.equals(decoded)) return TextNode.valueOf(Base64.getEncoder().encodeToString(mapped.getBytes(StandardCharsets.UTF_8)));
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            if (Set.of("dataeaseName", "fieldShortName").contains(key) && aliases.containsKey(text)) return TextNode.valueOf(aliases.get(text));
        }
        return node;
    }
}
