package io.dataease.visualization.calculation;

import io.dataease.api.visualization.dto.DashboardCalculationConfig;
import io.dataease.engine.trans.Quota2SQLObj;
import io.dataease.engine.utils.Utils;
import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import io.dataease.extensions.datasource.model.SQLMeta;
import io.dataease.extensions.datasource.model.SQLObj;
import io.dataease.extensions.view.dto.ChartViewFieldDTO;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class DashboardParameterQueryTest {
    @Test void pivotYearAndMonthPlacementsKeepSeparateValuesAndTotals() throws Exception {
        var year = new ChartViewFieldDTO();
        year.setId(11L); year.setDataeaseName("date_col"); year.setDeType(1); year.setDeExtractType(1); year.setType("datetime"); year.setDateStyle("y");
        var month = new ChartViewFieldDTO();
        month.setId(11L); month.setDataeaseName("date_col"); month.setDeType(1); month.setDeExtractType(1); month.setType("datetime"); month.setDateStyle("M");
        var amount = new ChartViewFieldDTO(); amount.setId(22L); amount.setDataeaseName("amount"); amount.setDeType(2);
        var view = new io.dataease.extensions.view.dto.ChartViewDTO(); view.setType("table-pivot");
        view.setXAxis(new ArrayList<>(List.of(year))); view.setXAxisExt(new ArrayList<>(List.of(month))); view.setYAxis(new ArrayList<>(List.of(amount))); view.setDrillFields(new ArrayList<>());
        new io.dataease.chart.charts.impl.GroupChartHandler().formatAxis(view);
        assertNotEquals(year.resultKey(), month.resultKey());
        String yearKey = year.resultKey(), monthKey = month.resultKey();
        new io.dataease.chart.charts.impl.GroupChartHandler().formatAxis(view);
        assertEquals(yearKey, year.resultKey()); assertEquals(monthKey, month.resultKey());
        String yearSql = io.dataease.engine.trans.Dimension2SQLObj.getXFields(year, "date_col", "year_value", false).getFieldName();
        String monthSql = io.dataease.engine.trans.Dimension2SQLObj.getXFields(month, "date_col", "month_value", false).getFieldName();
        String query = "SELECT " + yearSql + "," + monthSql + ",SUM(amount) FROM sales GROUP BY " + yearSql + "," + monthSql + " ORDER BY 1,2";
        var parser = org.apache.calcite.sql.parser.SqlParser.create(query, org.apache.calcite.sql.parser.SqlParser.Config.DEFAULT.withLex(org.apache.calcite.config.Lex.JAVA));
        String mysqlQuery = parser.parseStmt().toSqlString(org.apache.calcite.sql.dialect.MysqlSqlDialect.DEFAULT).toString();
        assertTrue(mysqlQuery.contains("%Y-%m"), mysqlQuery);
        assertTrue(mysqlQuery.toUpperCase().contains("SUBSTRING"), mysqlQuery);
        assertFalse(mysqlQuery.contains("'MM'"), mysqlQuery);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/dashboard-phase1/pivot-month-mysql.sql"), mysqlQuery);
        List<String[]> data = new ArrayList<>();
        try (var connection = database(); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM sales");
            statement.execute("INSERT INTO sales VALUES ('2025-01-01',10),('2025-10-01',20),('2026-01-01',30),('2026-02-01',5),('2026-10-01',40),('2026-10-20',10),('2026-12-01',25)");
            statement.execute("CREATE ALIAS IF NOT EXISTS DATE_FORMAT FOR 'io.dataease.visualization.calculation.DashboardParameterQueryTest.mysqlDateFormat'");
            try (var rows = statement.executeQuery(mysqlQuery)) {
                while (rows.next()) data.add(new String[]{ rows.getString(1), rows.getString(2), rows.getString(3) });
            }
        }
        var result = io.dataease.extensions.view.util.ChartDataUtil.transTableNormal(List.of(year, month, amount), view, data, Map.of());
        var table = (List<Map<String, Object>>) result.get("tableRow");
        assertEquals(List.of("01", "10", "01", "02", "10", "12"), table.stream().map(row -> row.get(monthKey)).toList());
        var expected = Map.of("2025-01",10, "2025-10",20, "2026-01",30, "2026-02",5, "2026-10",50, "2026-12",25);
        int grandTotal = 0, yearTotal = 0;
        for (var row : table) {
            int value = ((java.math.BigDecimal) row.get("amount")).intValueExact();
            assertEquals(expected.get(row.get(yearKey) + "-" + row.get(monthKey)), value);
            grandTotal += value;
            if ("2026".equals(row.get(yearKey))) yearTotal += value;
        }
        assertEquals(140, grandTotal); assertEquals(110, yearTotal);
        var reopened = JsonUtilForPivot(view);
        assertEquals(yearKey, reopened.getXAxis().getFirst().resultKey());
        assertEquals(monthKey, reopened.getXAxisExt().getFirst().resultKey());
    }

    public static String mysqlDateFormat(java.sql.Timestamp value, String pattern) {
        if (value == null) return null;
        String javaPattern = pattern.replace("%Y", "yyyy").replace("%m", "MM").replace("%d", "dd").replace("%H", "HH").replace("%i", "mm").replace("%s", "ss");
        return value.toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern(javaPattern));
    }

    private io.dataease.extensions.view.dto.ChartViewDTO JsonUtilForPivot(io.dataease.extensions.view.dto.ChartViewDTO view) {
        return io.dataease.utils.JsonUtil.parseObject((String) io.dataease.utils.JsonUtil.toJSONString(view), io.dataease.extensions.view.dto.ChartViewDTO.class);
    }
    private static final Instant REFERENCE = Instant.parse("2026-10-10T00:00:00Z");

    private List<DatasetTableFieldDTO> sourceFields() {
        var date = DashboardCalculationTest.publicFields().get(10L).get(0);
        date.setDeType(1);
        var amount = new DatasetTableFieldDTO();
        amount.setId(22L); amount.setName("业绩"); amount.setDataeaseName("amount"); amount.setExtField(0); amount.setDeType(2);
        return List.of(date, amount);
    }

    private DashboardCalculationConfig config() {
        var config = DashboardCalculationTest.example();
        var total = new DashboardCalculationConfig.SharedField();
        total.setId(900000000000000013L); total.setName("YTD业绩"); total.setDatasetGroupId(10L);
        total.setGroupType("q"); total.setDeType(2);
        total.setExpression("sum(if([YTD期间]=1,[业绩],0))");
        config.getDatasetFields().add(total);
        return new DashboardCalculationValidator().validate(config, Map.of(10L, sourceFields()));
    }

    private List<ChartViewFieldDTO> bound(DashboardCalculationConfig config, Map<String, String> values) {
        var fields = DashboardCalculationManage.toFields(config, 100L, 10L);
        new DashboardParameterCompiler().bind(config, values, REFERENCE, fields);
        return fields;
    }

    private List<DatasetTableFieldDTO> origins(List<ChartViewFieldDTO> fields) {
        List<DatasetTableFieldDTO> origins = new ArrayList<>(sourceFields()); origins.addAll(fields); return origins;
    }

    private Connection database() throws SQLException {
        var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sales (date_col DATE, amount BIGINT)");
            statement.execute("INSERT INTO sales VALUES ('2026-03-31',10),('2026-04-01',20),('2026-09-30',30),('2026-10-04',40),('2026-10-05',50)");
        }
        return connection;
    }

    @Test void guanyuanPeriodFormulaExecutesBeforeAggregationWithInclusiveDates() throws Exception {
        var config = config();
        var fields = bound(config, null);
        var table = new SQLObj(); table.setTableAlias("t");
        String period = Utils.buildCalcField(fields.get(0), table, origins(fields), 0, true, null, null, true, fields.get(0).getOriginName());
        assertFalse(period.contains("DVP."));
        try (var connection = database(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT SUM(" + period + "),SUM((" + period + ")*t.amount) FROM sales t")) {
            assertTrue(rows.next()); assertEquals(3, rows.getInt(1)); assertEquals(90, rows.getInt(2));
        }
        assertTrue(config.getDatasetFields().get(0).getExpression().contains("[DVP."), "definitions must remain unbound");
    }

    @Test void existingAggregatePipelineDoesNotAddASecondAggregationAndValuesStayPerQuery() throws Exception {
        var config = config();
        for (var test : List.of(Map.of("900000000000000011", "2026-10-04", "expected", "90"),
                                 Map.of("900000000000000011", "2026-09-30", "expected", "50"))) {
            var fields = bound(config, Map.of("900000000000000011", test.get("900000000000000011")));
            var metric = fields.get(1); metric.setSummary(""); metric.setAgg(true);
            var meta = new SQLMeta(); var table = new SQLObj(); table.setTableAlias("t"); meta.setTable(table);
            Quota2SQLObj.quota2sqlObj(meta, List.of(metric), origins(fields), true, null, List.of(), List.of(), null);
            String expression = meta.getYFields().get(0).getFieldName();
            assertEquals(1, java.util.regex.Pattern.compile("(?i)sum\\s*\\(").matcher(expression).results().count());
            try (var connection = database(); var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT " + expression + " FROM sales t")) {
                assertTrue(row.next()); assertEquals(Integer.parseInt(test.get("expected")), row.getInt(1));
            }
        }
    }

    @Test void privateFieldsCanUseParametersAndSharedFieldDependencies() throws Exception {
        var config = config(); var fields = DashboardCalculationManage.toFields(config, 100L, 10L);
        var privateField = new ChartViewFieldDTO(); privateField.setId(14L); privateField.setExtField(2);
        privateField.setOriginName("sum(if(and([11]>=[DYNAMIC_PARAMS.期初],[900000000000000012]=1),[22],0))");
        fields.add(privateField);
        new DashboardParameterCompiler().bind(config, null, REFERENCE, fields);
        var table = new SQLObj(); table.setTableAlias("t");
        String expression = Utils.buildCalcField(privateField, table, origins(fields), 0, true, null, null, true, privateField.getOriginName());
        try (var connection = database(); var statement = connection.createStatement(); var row = statement.executeQuery("SELECT " + expression + " FROM sales t")) {
            assertTrue(row.next()); assertEquals(90, row.getInt(1));
        }
    }

    @Test void invalidDatesForeignParametersAndRequiredEmptyValuesFailBeforeSql() {
        var config = config();
        assertThrows(IllegalArgumentException.class, () -> bound(config, Map.of("foreign", "2026-10-04")));
        assertThrows(RuntimeException.class, () -> bound(config, Map.of("900000000000000011", "2026-10-04' OR 1=1 --")));
        assertThrows(RuntimeException.class, () -> bound(config, Map.of("900000000000000011", "2026-02-30")));
        assertThrows(IllegalArgumentException.class, () -> bound(config, Map.of("900000000000000011", "")));
        config.getParameters().get(1).setRequired(false);
        assertTrue(bound(config, Map.of("900000000000000011", "")).get(0).getOriginName().contains("CAST(NULL AS DATE)"));
    }
}
