package io.dataease.engine.sql;

import io.dataease.constant.SQLConstants;
import io.dataease.extensions.datasource.model.SQLMeta;
import io.dataease.extensions.datasource.model.SQLObj;
import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.extensions.view.dto.SortAxis;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.STGroupString;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @Author Junjun
 * <p>
 * 将SQLMeta各个部分先构建完毕，然后在这个类中拼接
 */
public class SQLProvider {

    /**
     * @param sqlMeta sql作为table，首尾用'(',')'
     * @param isGroup 是否聚合
     * @return
     */
    public static String createQuerySQLAsTmp(SQLMeta sqlMeta, boolean isGroup, boolean needOrder, boolean distinct) {
        return createQuerySQL(sqlMeta, isGroup, needOrder, distinct);
    }

    public static String createQuerySQLWithLimit(SQLMeta sqlMeta, boolean isGroup, boolean needOrder, boolean distinct, int start, int count) {
        return createQuerySQL(sqlMeta, isGroup, needOrder, distinct) + " LIMIT " + count + " OFFSET " + start;
    }

    public static String createQuerySQL(SQLMeta sqlMeta, boolean isGroup, boolean needOrder, boolean distinct) {
        List<SQLObj> xFields = sqlMeta.getXFields();
        SQLObj tableObj = sqlMeta.getTable();
        List<SQLObj> xOrders = sqlMeta.getXOrders();

        STGroup stg = new STGroupString(SqlTemplate.PREVIEW_SQL);
        ST st_sql = stg.getInstanceOf("previewSql");
        st_sql.add("isGroup", isGroup);
        st_sql.add("distinct", distinct);
        if (ObjectUtils.isNotEmpty(xFields)) st_sql.add("groups", xFields);
        addGroupByFields(st_sql, xFields, null);
        if (ObjectUtils.isNotEmpty(tableObj)) st_sql.add("table", tableObj);
        String customWheres = sqlMeta.getCustomWheres();
        String extWheres = sqlMeta.getExtWheres();
        String whereTrees = sqlMeta.getWhereTrees();
        List<String> wheres = new ArrayList<>();
        if (customWheres != null) wheres.add(customWheres);
        if (extWheres != null) wheres.add(extWheres);
        if (whereTrees != null) wheres.add(whereTrees);
        if (ObjectUtils.isNotEmpty(wheres)) st_sql.add("filters", wheres);

        // check datasource 是否需要排序
        if (needOrder && ObjectUtils.isEmpty(xOrders)) {
            if (ObjectUtils.isNotEmpty(xFields)) {
                xOrders = new ArrayList<>();
                SQLObj sqlObj = xFields.get(0);
                SQLObj result = SQLObj.builder()
                        .orderField(String.format(SQLConstants.FIELD_DOT, sqlObj.getFieldAlias()))
                        .orderAlias(String.format(SQLConstants.FIELD_DOT, sqlObj.getFieldAlias()))
                        .orderDirection("ASC").build();
                xOrders.add(result);
            }
        }
        if (ObjectUtils.isNotEmpty(xOrders)) {
            st_sql.add("orders", xOrders);
        }

        return st_sql.render();
    }

    public static String createQuerySQL(SQLMeta sqlMeta, boolean isGroup, boolean needOrder, ChartViewDTO view) {
        STGroup stg = new STGroupString(SqlTemplate.PREVIEW_SQL);
        ST st_sql = stg.getInstanceOf("previewSql");

        st_sql.add("isGroup", isGroup);

        SQLObj tableObj = sqlMeta.getTable();
        if (ObjectUtils.isNotEmpty(tableObj)) st_sql.add("table", tableObj);

        List<SQLObj> xFields = sqlMeta.getXFields();
        List<SQLObj> xOrders = sqlMeta.getXOrders();
        if (ObjectUtils.isNotEmpty(xFields)) st_sql.add("groups", xFields);

        List<SQLObj> yFields = sqlMeta.getYFields();
        List<String> yWheres = sqlMeta.getYWheres();
        List<SQLObj> yOrders = sqlMeta.getYOrders();
        if (ObjectUtils.isNotEmpty(yFields)) st_sql.add("aggregators", yFields);
        addGroupByFields(st_sql, xFields, yFields);

        String customWheres = sqlMeta.getCustomWheres();
        String extWheres = sqlMeta.getExtWheres();
        String whereTrees = sqlMeta.getWhereTrees();
        List<String> wheres = new ArrayList<>();
        if (customWheres != null) wheres.add(customWheres);
        if (extWheres != null) wheres.add(extWheres);
        if (whereTrees != null) wheres.add(whereTrees);
        if (ObjectUtils.isNotEmpty(wheres)) st_sql.add("filters", wheres);
        String sql = st_sql.render();

        ST st = stg.getInstanceOf("previewSql");
        st_sql.add("isGroup", isGroup);

        SQLObj tableSQL = SQLObj.builder()
                .tableName(String.format(SQLConstants.BRACKETS, sql))
                .tableAlias(String.format(SQLConstants.TABLE_ALIAS_PREFIX, 1))
                .build();
        if (ObjectUtils.isNotEmpty(tableSQL)) st.add("table", tableSQL);

        List<String> aggWheres = new ArrayList<>();
        if (ObjectUtils.isNotEmpty(yWheres)) aggWheres.addAll(yWheres);
        if (ObjectUtils.isNotEmpty(aggWheres)) st.add("filters", aggWheres);

        List<SQLObj> orders = new ArrayList<>();
        if (ObjectUtils.isNotEmpty(xOrders)) orders.addAll(xOrders);
        if (ObjectUtils.isNotEmpty(yOrders)) orders.addAll(yOrders);
        if (!orders.isEmpty() && CollectionUtils.isNotEmpty(view.getSortPriority())) {
            var sortPriority = view.getSortPriority();
            var tmp = new ArrayList<SQLObj>();
            var ids = new HashSet<Long>();
            for (SortAxis sortAxis : sortPriority) {
                for (SQLObj order : orders) {
                    if (sortAxis.getId().equals(order.getId())){
                        tmp.add(order);
                        ids.add(order.getId());
                    }
                }
            }
            for (SQLObj order : orders) {
                if (!ids.contains(order.getId())) {
                    tmp.add(order);
                }
            }
            orders = tmp;
        }
        // check datasource 是否需要排序
        if (needOrder && ObjectUtils.isEmpty(orders)) {
            if (ObjectUtils.isNotEmpty(xFields) || ObjectUtils.isNotEmpty(yFields)) {
                SQLObj sqlObj = ObjectUtils.isNotEmpty(xFields) ? xFields.get(0) : yFields.get(0);
                SQLObj result = SQLObj.builder()
                        .orderField(String.format(SQLConstants.FIELD_DOT, sqlObj.getFieldAlias()))
                        .orderAlias(String.format(SQLConstants.FIELD_DOT, sqlObj.getFieldAlias()))
                        .orderDirection("ASC").build();
                orders.add(result);
            }
        }
        orders = stableCategoryOrders(view, xFields, orders);
        if (ObjectUtils.isNotEmpty(orders)) st.add("orders", orders);

        return sqlLimit(st.render(), view);
    }

    public static String createQuerySQLNoSort(SQLMeta sqlMeta, boolean isGroup, ChartViewDTO view) {
        STGroup stg = new STGroupString(SqlTemplate.PREVIEW_SQL);
        ST st_sql = stg.getInstanceOf("previewSql");

        st_sql.add("isGroup", isGroup);

        SQLObj tableObj = sqlMeta.getTable();
        if (ObjectUtils.isNotEmpty(tableObj)) st_sql.add("table", tableObj);

        List<SQLObj> xFields = sqlMeta.getXFields();
        if (ObjectUtils.isNotEmpty(xFields)) st_sql.add("groups", xFields);

        List<SQLObj> yFields = sqlMeta.getYFields();
        List<String> yWheres = sqlMeta.getYWheres();
        if (ObjectUtils.isNotEmpty(yFields)) st_sql.add("aggregators", yFields);
        addGroupByFields(st_sql, xFields, yFields);

        String customWheres = sqlMeta.getCustomWheres();
        String extWheres = sqlMeta.getExtWheres();
        String whereTrees = sqlMeta.getWhereTrees();
        List<String> wheres = new ArrayList<>();
        if (customWheres != null) wheres.add(customWheres);
        if (extWheres != null) wheres.add(extWheres);
        if (whereTrees != null) wheres.add(whereTrees);
        if (ObjectUtils.isNotEmpty(wheres)) st_sql.add("filters", wheres);
        String sql = st_sql.render();

        ST st = stg.getInstanceOf("previewSql");
        st_sql.add("isGroup", isGroup);

        SQLObj tableSQL = SQLObj.builder()
                .tableName(String.format(SQLConstants.BRACKETS, sql))
                .tableAlias(String.format(SQLConstants.TABLE_ALIAS_PREFIX, 1))
                .build();
        if (ObjectUtils.isNotEmpty(tableSQL)) st.add("table", tableSQL);

        List<String> aggWheres = new ArrayList<>();
        if (ObjectUtils.isNotEmpty(yWheres)) aggWheres.addAll(yWheres);
        if (ObjectUtils.isNotEmpty(aggWheres)) st.add("filters", aggWheres);

        return sqlLimit(st.render(), view);
    }

    private static void addGroupByFields(ST sql, List<SQLObj> xFields, List<SQLObj> yFields) {
        List<SQLObj> groupByFields = new ArrayList<>();
        if (ObjectUtils.isNotEmpty(xFields)) {
            groupByFields.addAll(xFields);
        }
        if (ObjectUtils.isNotEmpty(yFields)) {
            yFields.stream()
                    .filter(field -> StringUtils.isNotBlank(field.getGroupField()))
                    .map(field -> SQLObj.builder()
                            .fieldName(field.getGroupField())
                            .fieldAlias(field.getGroupAlias())
                            .build())
                    .forEach(groupByFields::add);
        }
        if (!groupByFields.isEmpty()) {
            sql.add("groupBys", groupByFields);
        }
    }

    public static String sqlLimit(String sql, ChartViewDTO view) {
        if (StringUtils.equalsAnyIgnoreCase(view.getType(), "table-info", "table-normal")) {
            return sql;
        }
        if (StringUtils.equalsIgnoreCase(view.getResultMode(), "custom")) {
            return sql + " LIMIT " + view.getResultCount() + " OFFSET 0";
        } else {
            return sql;
        }
    }

    // A single category has one aggregate row. Keep nulls last in either direction,
    // then break equal metric values by category before LIMIT selects the members.
    static List<SQLObj> stableCategoryOrders(ChartViewDTO view, List<SQLObj> dimensions, List<SQLObj> orders) {
        if (isGlobalPivotSort(view) && CollectionUtils.isNotEmpty(dimensions) && CollectionUtils.isNotEmpty(orders)) {
            SQLObj primary = orders.getFirst();
            if (!StringUtils.equalsAnyIgnoreCase(primary.getOrderDirection(), "asc", "desc")) return orders;
            List<SQLObj> stable = new ArrayList<>();
            stable.add(nullsLast(primary.getOrderAlias()));
            stable.add(primary);
            for (SQLObj dimension : dimensions) {
                String alias = String.format(SQLConstants.FIELD_DOT, dimension.getFieldAlias());
                if (!alias.replace("`", "").equals(primary.getOrderAlias().replace("`", ""))) {
                    stable.add(nullsLast(alias));
                    stable.add(SQLObj.builder().orderAlias(alias).orderDirection("ASC").build());
                }
            }
            return stable;
        }
        if (view.getType() == null || !Set.of("bar", "bar-horizontal", "bar-group", "bar-stack", "bar-group-stack",
                "bar-percentage", "bar-group-horizontal", "bar-stack-horizontal", "bar-percentage-horizontal",
                "line", "area", "area-stack", "pie", "pie-donut").contains(view.getType())
                || dimensions == null || dimensions.size() != 1 || orders.size() != 1
                || CollectionUtils.isNotEmpty(view.getXAxisExt()) || CollectionUtils.isNotEmpty(view.getExtStack())
                || CollectionUtils.isNotEmpty(view.getExtBubble())) return orders;
        SQLObj primary = orders.getFirst();
        if (!StringUtils.equalsAnyIgnoreCase(primary.getOrderDirection(), "asc", "desc")) return orders;
        List<SQLObj> stable = new ArrayList<>();
        stable.add(nullsLast(primary.getOrderAlias()));
        stable.add(primary);
        String category = String.format(SQLConstants.FIELD_DOT, dimensions.getFirst().getFieldAlias());
        if (!category.replace("`", "").equals(primary.getOrderAlias().replace("`", ""))) {
            stable.add(nullsLast(category));
            stable.add(SQLObj.builder().orderAlias(category).orderDirection("ASC").build());
        }
        return stable;
    }

    private static boolean isGlobalPivotSort(ChartViewDTO view) {
        return StringUtils.equalsIgnoreCase(view.getType(), "table-pivot")
                && view.getXAxis() != null && view.getXAxis().size() >= 2
                && CollectionUtils.isEmpty(view.getXAxisExt())
                && CollectionUtils.isNotEmpty(view.getSortPriority())
                && StringUtils.equalsIgnoreCase(view.getSortPriority().getFirst().getAxis(), "yAxis")
                && StringUtils.equalsIgnoreCase(view.getSortPriority().getFirst().getScope(), "GLOBAL");
    }

    private static SQLObj nullsLast(String alias) {
        return SQLObj.builder().orderAlias("CASE WHEN " + alias + " IS NULL THEN 1 ELSE 0 END")
                .orderDirection("ASC").build();
    }
}
