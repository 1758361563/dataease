package io.dataease.company.bi.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.company.bi.admin.CompanyAdminErrors;
import io.dataease.company.bi.permission.CompanyDashboardGuard;
import io.dataease.company.bi.permission.CompanyExportKind;
import io.dataease.company.bi.permission.PermissionDecisionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = io.dataease.company.bi.auth.CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class FeishuSubscriptionContent {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final CompanyDashboardGuard guard;
    private final PermissionDecisionService permissions;

    public FeishuSubscriptionContent(JdbcTemplate jdbc, CompanyDashboardGuard guard, PermissionDecisionService permissions) {
        this.jdbc = jdbc; this.guard = guard; this.permissions = permissions;
    }

    public List<Map<String, Object>> dashboards() {
        long user = guard.currentUserId();
        return jdbc.query("SELECT id,name FROM data_visualization_info WHERE node_type='leaf' AND type='dashboard' AND (delete_flag=0 OR delete_flag IS NULL) ORDER BY name,id",
                (rs, i) -> Map.<String, Object>of("id", rs.getString("id"), "name", rs.getString("name")))
                .stream().filter(row -> permissions.canViewDashboard(user, Long.valueOf((String) row.get("id")))).toList();
    }

    public Map<String, Object> options(Long id) {
        guard.requireView(id);
        var rows = jdbc.queryForList("SELECT name,component_data FROM data_visualization_info WHERE id=? AND node_type='leaf' AND type='dashboard' AND (delete_flag=0 OR delete_flag IS NULL)", id);
        if (rows.isEmpty()) CompanyAdminErrors.invalid("仪表板不存在");
        var cards = jdbc.query("SELECT id,title FROM core_chart_view WHERE scene_id=? AND table_id IS NOT NULL ORDER BY id",
                (rs, i) -> Map.<String, Object>of("id", rs.getString("id"), "name", Objects.toString(rs.getString("title"), "未命名卡片")), id);
        List<Map<String, Object>> filters = new ArrayList<>();
        try { collectFilters(JSON.readTree(Objects.toString(rows.getFirst().get("component_data"), "[]")), filters); }
        catch (Exception e) { CompanyAdminErrors.invalid("仪表板筛选配置无法读取，请重新保存仪表板"); }
        return Map.of("name", rows.getFirst().get("name"), "cards", cards, "filters", filters);
    }

    public void requireExecution(Long subscriptionId) {
        var rows = jdbc.queryForList("SELECT dashboard_id,created_by,updated_by,status FROM company_feishu_subscription WHERE id=?", subscriptionId);
        if (rows.isEmpty()) CompanyAdminErrors.invalid("订阅不存在");
        var row = rows.getFirst();
        if (!"ENABLED".equals(row.get("status"))) CompanyAdminErrors.invalid("订阅已停用");
        long dashboard = Long.parseLong(String.valueOf(row.get("dashboard_id")));
        long owner = ((Number) Objects.requireNonNullElse(row.get("updated_by"), row.get("created_by"))).longValue();
        if (!permissions.canViewDashboard(owner, dashboard) || !permissions.canExportDashboard(owner, dashboard, CompanyExportKind.IMAGE_PDF_TEMPLATE))
            CompanyAdminErrors.invalid("订阅配置者已无此仪表板的查看或图片导出权限，请重新配置订阅");
        guard.requireExport(dashboard, CompanyExportKind.IMAGE_PDF_TEMPLATE);
    }

    private static void collectFilters(JsonNode node, List<Map<String, Object>> filters) {
        if (node == null) return;
        if (node.isObject() && "VQuery".equals(node.path("component").asText())) {
            for (var field : node.path("propValue")) {
                if (field.path("id").asText().isBlank() || field.path("name").asText().isBlank()) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", field.path("id").asText()); item.put("name", field.path("name").asText());
                item.put("displayType", field.path("displayType").asText());
                item.put("multiple", field.path("multiple").asBoolean());
                item.put("conditionType", field.path("conditionType").asInt());
                item.put("valueSource", field.path("valueSource").isArray() ? JSON.convertValue(field.path("valueSource"), Object.class) : List.of());
                filters.add(item);
            }
        }
        if (node.isContainerNode()) node.forEach(child -> collectFilters(child, filters));
    }

    public Map<String, Object> normalize(String dashboardId, Object raw, String legacyChartId) {
        Long id;
        try { id = Long.valueOf(dashboardId); } catch (Exception e) { CompanyAdminErrors.invalid("请选择仪表板"); return Map.of(); }
        guard.requireExport(id, CompanyExportKind.IMAGE_PDF_TEMPLATE);
        JsonNode input = JSON.valueToTree(raw);
        String mode = input == null || input.isNull() ? "CARDS" : input.path("mode").asText();
        if (!Set.of("CARDS", "DASHBOARD").contains(mode)) CompanyAdminErrors.invalid("请选择订阅内容类型");
        var options = options(id);
        @SuppressWarnings("unchecked") var cards = (List<Map<String, Object>>) options.get("cards");
        List<String> selected = new ArrayList<>();
        if ("CARDS".equals(mode)) {
            if (input == null || input.isNull()) selected.add(legacyChartId);
            else input.path("chartIds").forEach(value -> selected.add(value.asText()));
            if (selected.isEmpty() || selected.size() > 12 || new HashSet<>(selected).size() != selected.size())
                CompanyAdminErrors.invalid("请选择 1 至 12 张不同的卡片");
            for (String chart : selected) if (cards.stream().noneMatch(c -> Objects.equals(c.get("id"), chart)))
                CompanyAdminErrors.invalid("所选卡片已删除或不属于此仪表板，请重新选择");
        }
        return Map.of("version", 1, "mode", mode, "chartIds", selected, "dashboardName", options.get("name"), "cardNames", cards.stream().filter(c -> selected.contains(c.get("id"))).toList());
    }
}
