package io.dataease.dataset.manage;

import io.dataease.api.dataset.union.DatasetGroupInfoDTO;
import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import io.dataease.extensions.view.dto.DatasetRowPermissionsTreeItem;
import io.dataease.extensions.view.dto.DatasetRowPermissionsTreeObj;
import lombok.Data;
import java.math.BigDecimal;
import java.util.*;

/** Query-only state. Never accepted by dataset save operations. */
@Data
public class DatasetPreviewRequest {
    private Long id;
    private DatasetGroupInfoDTO draft;
    private List<Rule> rules = new ArrayList<>();
    private String logic = "and";
    private List<String> columnIds;
    private int page = 1;
    private int size = 100;
    private String valueFieldId;
    private String searchText;

    @Data
    public static class Rule {
        private String fieldId;
        private String operator = "eq";
        private Integer deType;
        private List<String> values = new ArrayList<>();
    }

    public DatasetRowPermissionsTreeObj conditionTree(List<DatasetTableFieldDTO> fields) {
        if (!Set.of("and", "or").contains(logic) || rules == null || rules.size() > 100) {
            throw new IllegalArgumentException("预览筛选规则无效或过多");
        }
        Map<String, DatasetTableFieldDTO> byId = new HashMap<>();
        fields.forEach(f -> byId.put(String.valueOf(f.getId()), f));
        List<DatasetRowPermissionsTreeItem> items = new ArrayList<>();
        for (Rule rule : rules) {
            DatasetTableFieldDTO field = byId.get(rule.fieldId);
            if (field == null) throw new IllegalArgumentException("筛选字段已失效，请重新选择");
            if (rule.deType != null && !rule.deType.equals(field.getDeType())) throw new IllegalArgumentException("筛选字段类型已改变，请重新配置规则");
            String op = rule.operator;
            if (!Set.of("eq", "not_eq", "lt", "le", "gt", "ge", "like", "not like", "null", "not_null", "empty", "not_empty", "in", "not in", "between").contains(op)) {
                throw new IllegalArgumentException("不支持的筛选条件");
            }
            List<String> values = rule.values;
            boolean noValue = Set.of("null", "not_null", "empty", "not_empty").contains(op);
            if (values == null || values.size() > 1000 || (!noValue && values.isEmpty())) {
                throw new IllegalArgumentException("请填写筛选值（每条最多1000个）");
            }
            if (!noValue) for (String value : values) {
                if (value == null || value.length() > 10000) throw new IllegalArgumentException("筛选值无效或过长");
                if (Set.of(2, 3, 4).contains(field.getDeType())) new BigDecimal(value);
                if (field.getDeType() == 1) java.time.LocalDateTime.parse(value.replace(' ', 'T'));
            }
            if ("between".equals(op)) {
                if (values.size() != 2) throw new IllegalArgumentException("范围需要起止两个值");
                if (Set.of(2, 3, 4).contains(field.getDeType()) ? new BigDecimal(values.get(0)).compareTo(new BigDecimal(values.get(1))) > 0 : values.get(0).compareTo(values.get(1)) > 0) throw new IllegalArgumentException("范围起始值不能大于结束值");
                DatasetRowPermissionsTreeItem group = new DatasetRowPermissionsTreeItem();
                group.setType("tree");
                DatasetRowPermissionsTreeObj range = new DatasetRowPermissionsTreeObj();
                range.setLogic("and");
                range.setItems(List.of(item(field, "ge", values.get(0)), item(field, "le", values.get(1))));
                group.setSubTree(range);
                items.add(group);
            } else if ("in".equals(op) || "not in".equals(op)) {
                // A subtree preserves commas in values and works with existing dialect conversion.
                DatasetRowPermissionsTreeItem group = new DatasetRowPermissionsTreeItem();
                group.setType("tree");
                DatasetRowPermissionsTreeObj selection = new DatasetRowPermissionsTreeObj();
                selection.setLogic("in".equals(op) ? "or" : "and");
                selection.setItems(values.stream().distinct().map(v -> item(field, "in".equals(op) ? "eq" : "not_eq", v)).toList());
                group.setSubTree(selection);
                items.add(group);
            } else {
                if (!noValue && values.size() != 1) throw new IllegalArgumentException("此条件只能填写一个值");
                items.add(item(field, op, noValue ? "" : values.getFirst()));
            }
        }
        DatasetRowPermissionsTreeObj tree = new DatasetRowPermissionsTreeObj();
        tree.setLogic(logic);
        tree.setItems(items);
        return tree;
    }

    private static DatasetRowPermissionsTreeItem item(DatasetTableFieldDTO field, String operator, String value) {
        DatasetRowPermissionsTreeItem item = new DatasetRowPermissionsTreeItem();
        item.setType("item");
        item.setFieldId(field.getId());
        item.setField(field);
        item.setFilterType("logic");
        item.setTerm(operator);
        item.setValue(value);
        item.setTimeType("second");
        return item;
    }
}
