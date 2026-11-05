package io.dataease.chart.utils;

import io.dataease.engine.trans.Dimension2SQLObj;
import io.dataease.extensions.view.dto.ChartViewFieldDTO;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import java.sql.DriverManager;
import java.util.Locale;

public class PivotWeekSqlTest {
    public static void main(String[] args) throws Exception {
        for (int extract : new int[]{1, 0, 2}) {
            var field = new ChartViewFieldDTO();
            field.setDeType(1); field.setDeExtractType(extract);
            field.setDateStyle("y_W"); field.setDatePattern("date_split");
            field.setDateFormat("yyyy-MM-dd HH:mm:ss");
            String expression = Dimension2SQLObj.getXFields(field, "t.d", "week", false).getFieldName();
            String sql = SqlParser.create("SELECT " + expression + " FROM t").parseQuery()
                    .toSqlString(MysqlSqlDialect.DEFAULT).getSql();
            if (!sql.contains("DAYOFWEEK") || !sql.contains("%Y-%m-%d"))
                throw new AssertionError("ISO Monday grouping not translated: " + sql);
            if (sql.contains("%Y-%u")) throw new AssertionError("Calendar year/week zero must not be used");
        }
        var month = new ChartViewFieldDTO(); month.setDeType(1); month.setDeExtractType(1);
        month.setDateStyle("y_M"); month.setDatePattern("date_sub");
        String sql = Dimension2SQLObj.getXFields(month, "t.d", "month", false).getFieldName();
        if (!sql.contains("yyyy-MM") || sql.contains("SUBSTRING"))
            throw new AssertionError("Year-month identity was lost: " + sql);
        testWeekdayGrouping();
        System.out.println("Weekly SQL dialect conversion and year-month grouping passed");
    }

    private static void testWeekdayGrouping() throws Exception {
        for (int extract : new int[]{1, 0, 2}) {
            var field = new ChartViewFieldDTO();
            field.setDeType(1); field.setDeExtractType(extract);
            field.setDateStyle("y_M_d"); field.setDateShowFormat("weekday_name");
            field.setDateFormat("yyyy-MM-dd HH:mm:ss");
            String expression = Dimension2SQLObj.getXFields(field, "t.d", "weekday", false).getFieldName();
            String sql = SqlParser.create("SELECT " + expression + " FROM t GROUP BY " + expression)
                    .parseQuery().toSqlString(MysqlSqlDialect.DEFAULT).getSql();
            if (!sql.contains("DAYOFWEEK") || !sql.contains("MOD("))
                throw new AssertionError("Weekday grouping missing: " + sql);
        }
        // Execute the actual generated expression against a local, ephemeral SQL engine.
        // Two Mondays in different months/years must share a column key; Sunday must be 7.
        var field = new ChartViewFieldDTO();
        field.setDeType(1); field.setDeExtractType(1);
        field.setDateStyle("y_M_d"); field.setDateShowFormat("weekday_name");
        String expression = Dimension2SQLObj.getXFields(field, "d", "weekday", false).getFieldName();
        // H2's DAYOFWEEK follows the JVM locale; emulate MySQL's Sunday=1 convention.
        Locale previousLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:pivot_weekdays;MODE=MySQL");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sample(d TIMESTAMP, sales INT)");
            statement.execute("INSERT INTO sample VALUES ('2025-12-29 12:00:00', 10), "
                    + "('2026-11-02 09:30:00', 20), ('2026-11-03 00:00:00', 3), "
                    + "('2026-11-04 00:00:00', 4), ('2026-11-05 00:00:00', 5), "
                    + "('2026-11-06 00:00:00', 6), ('2026-11-07 00:00:00', 7), ('2026-11-08 00:00:00', 8)");
            try (var rows = statement.executeQuery("SELECT " + expression + " AS weekday, SUM(sales) FROM sample GROUP BY "
                    + expression + " ORDER BY weekday")) {
                int key = 1;
                while (rows.next()) {
                    if (!String.valueOf(key).equals(rows.getString(1)) || rows.getInt(2) != (key == 1 ? 30 : key + 1))
                        throw new AssertionError("Incorrect weekday aggregation at " + key + ": key=" + rows.getString(1) + ", sum=" + rows.getInt(2));
                    key++;
                }
                if (key != 8) throw new AssertionError("Expected seven weekday groups");
            }
        } finally {
            Locale.setDefault(previousLocale);
        }
        field.setDateShowFormat(null);
        String dailyExpression = Dimension2SQLObj.getXFields(field, "d", "day", false).getFieldName();
        if (dailyExpression.contains("DAYOFWEEK"))
            throw new AssertionError("Ordinary daily grouping changed");
        field.setDateShowFormat("weekday_date_name");
        if (!dailyExpression.equals(Dimension2SQLObj.getXFields(field, "d", "day", false).getFieldName()))
            throw new AssertionError("Weekday label mode must preserve daily grouping");
        System.out.println("Weekday SQL grouping: native/text/timestamp dialects, seven groups and cross-year Monday sums passed");
    }
}
