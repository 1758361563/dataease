package io.dataease.chart.utils;

import io.dataease.engine.trans.Dimension2SQLObj;
import io.dataease.extensions.view.dto.ChartViewFieldDTO;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;

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
        System.out.println("Weekly SQL dialect conversion and year-month grouping passed");
    }
}
