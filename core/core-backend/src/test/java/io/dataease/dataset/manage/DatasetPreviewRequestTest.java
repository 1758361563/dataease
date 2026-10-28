package io.dataease.dataset.manage;

import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import io.dataease.extensions.datasource.model.SQLMeta;
import io.dataease.extensions.datasource.model.SQLObj;
import io.dataease.api.chart.dto.DeSortField;
import io.dataease.engine.sql.SQLProvider;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DatasetPreviewRequestTest {
    @Test void detailPaginationOrdersProjectedAliasesRatherThanAggregateExpressions() {
        SQLMeta meta = new SQLMeta();
        meta.setTable(SQLObj.builder().tableName("fixture").tableAlias("t").build());
        meta.setXFields(List.of(
                SQLObj.builder().id(1L).fieldName("t.id").fieldAlias("c0").build(),
                // Detail projections can contain placeholders instead of aggregate expressions.
                SQLObj.builder().id(2L).fieldName("NULL").fieldAlias("c1").build()));
        DatasetDataManage.applyPreviewOrder(meta, null, false);
        String sql = SQLProvider.createQuerySQLWithLimit(meta, false, false, false, 100, 100);
        assertTrue(sql.contains("ORDER BY"));
        assertTrue(sql.contains("c0"));
        assertFalse(sql.contains("SUM("));
        assertEquals(2, meta.getXOrders().size());
        assertTrue(meta.getXOrders().get(1).getOrderField().contains("c1"));
        DatasetDataManage.applyPreviewOrder(meta, null, true);
        assertTrue(meta.getXOrders().isEmpty());
        assertFalse(SQLProvider.createQuerySQL(meta, false, false, false).contains("ORDER BY"));
    }

    @Test void configuredPreviewSortsStayOnProjectedColumnsAndUseSafeDirections() {
        SQLMeta meta = new SQLMeta();
        meta.setXFields(List.of(SQLObj.builder().id(2L).fieldName("NULL").fieldAlias("c1").build()));
        DeSortField sort = new DeSortField();
        sort.setId(2L);
        sort.setOrderDirection("desc; SELECT 1");
        DatasetDataManage.applyPreviewOrder(meta, List.of(sort), false);
        assertEquals("asc", meta.getXOrders().getFirst().getOrderDirection());
        sort.setOrderDirection("DESC");
        DatasetDataManage.applyPreviewOrder(meta, List.of(sort), false);
        assertEquals("desc", meta.getXOrders().getFirst().getOrderDirection());
    }
    private DatasetTableFieldDTO field(int type) {
        DatasetTableFieldDTO field = new DatasetTableFieldDTO();
        field.setId(9007199254740993L);
        field.setDeType(type);
        return field;
    }
    private DatasetPreviewRequest request(String operator, String... values) {
        DatasetPreviewRequest request = new DatasetPreviewRequest();
        DatasetPreviewRequest.Rule rule = new DatasetPreviewRequest.Rule();
        rule.setFieldId("9007199254740993");
        rule.setOperator(operator);
        rule.setValues(List.of(values));
        request.setRules(List.of(rule));
        return request;
    }
    @Test void selectionPreservesCommaQuotesAndLongFieldIds() {
        var tree = request("in", "a,b", "O'Reilly").conditionTree(List.of(field(0)));
        var choices = tree.getItems().getFirst().getSubTree();
        assertEquals("or", choices.getLogic());
        assertEquals("a,b", choices.getItems().getFirst().getValue());
        assertEquals(9007199254740993L, choices.getItems().getFirst().getFieldId());
    }
    @Test void exclusionUsesAndAndRangeIncludesBothBoundaries() {
        var tree = request("not in", "a", "b").conditionTree(List.of(field(0)));
        assertEquals("and", tree.getItems().getFirst().getSubTree().getLogic());
        assertEquals("not_eq", tree.getItems().getFirst().getSubTree().getItems().getFirst().getTerm());
        var range = request("between", "1", "10").conditionTree(List.of(field(2))).getItems().getFirst().getSubTree();
        assertEquals(List.of("ge", "le"), range.getItems().stream().map(item -> item.getTerm()).toList());
    }
    @Test void rejectsMissingFieldsAndSqlOperatorsRatherThanIgnoringThem() {
        assertThrows(IllegalArgumentException.class, () -> request("eq", "x").conditionTree(List.of()));
        assertThrows(IllegalArgumentException.class, () -> request("eq); DROP TABLE x; --", "x").conditionTree(List.of(field(0))));
        var invalidLogic = request("eq", "x");
        invalidLogic.setLogic("or 1=1");
        assertThrows(IllegalArgumentException.class, () -> invalidLogic.conditionTree(List.of(field(0))));
    }
    @Test void validatesNumericAndMissingValuesButNullNeedsNoValue() {
        assertThrows(NumberFormatException.class, () -> request("ge", "abc").conditionTree(List.of(field(2))));
        assertThrows(IllegalArgumentException.class, () -> request("between", "1").conditionTree(List.of(field(2))));
        assertThrows(IllegalArgumentException.class, () -> request("eq").conditionTree(List.of(field(0))));
        assertEquals("null", request("null").conditionTree(List.of(field(0))).getItems().getFirst().getTerm());
    }
}
