package io.dataease.visualization.calculation;

import io.dataease.api.visualization.dto.DashboardCalculationConfig;
import io.dataease.extensions.view.dto.ChartViewFieldDTO;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Standalone regression; emitted SELECTs can also be executed against MySQL without changing data. */
public class DashboardFormulaDateArithmeticTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        var config = new DashboardCalculationConfig();
        var parameter = new DashboardCalculationConfig.Parameter();
        parameter.setId(1L); parameter.setName("周期末"); parameter.setType("DATE"); parameter.setRequired(true);
        config.setParameters(List.of(parameter));
        var field = new ChartViewFieldDTO(); field.setFieldScope("DASHBOARD");
        field.setOriginName("if(and([11]>=DATE_SUB([DVP.1],7),[11]<=ADD_MONTHS([DVP.1],-12)),1,0)");
        var defaults = new DashboardCalculationConfig.DefaultDefinition();
        defaults.setMode("FIXED"); defaults.setValue("2026-10-11"); parameter.setDefaultDefinition(defaults);
        new DashboardParameterCompiler().bind(config, null, Instant.parse("2026-10-15T00:00:00Z"), List.of(field));
        String bound = field.getOriginName();
        check(bound.contains("TIMESTAMPADD(DAY,-(7),CAST('2026-10-11' AS DATE))"), bound);
        check(bound.contains("TIMESTAMPADD(MONTH,(-12),CAST('2026-10-11' AS DATE))"), bound);
        check(!bound.contains("DVP."), bound);
        String quoted = "'DATE_SUB(x,7),ADD_MONTHS(y,-12)'";
        check(DashboardFormulaSyntax.sql(quoted).equals(quoted), "quoted text changed");
        String interval = "DATE_SUB(CAST('2026-10-11' AS DATE),INTERVAL 7 DAY)";
        check(DashboardFormulaSyntax.sql(interval).equals(interval), "native interval changed");
        for (String invalid : List.of("DATE_SUB(x)", "ADD_MONTHS(x,1,2)")) {
            try { DashboardFormulaSyntax.sql(invalid); throw new AssertionError(invalid); }
            catch (IllegalArgumentException expected) { }
        }
        String[] formulas = {
            "DATE_SUB(CAST('2026-10-05' AS DATE),7)",
            "DATE_SUB(CAST('2026-10-11' AS DATE),7)",
            "ADD_MONTHS(CAST('2026-10-01' AS DATE),-12)",
            "ADD_MONTHS(CAST('2024-02-29' AS DATE),-12)",
            "ADD_MONTHS(CAST('2026-03-31' AS DATE),-1)",
            "DATE_SUB(CAST('2026-10-11' AS DATE),-7)",
            "DATE_SUB(CAST('2026-10-11' AS DATE),1+6)"
        };
        var queries = new ArrayList<String>();
        for (String formula : formulas) {
            String sql = SqlParser.create("SELECT " + DashboardFormulaSyntax.sql(formula)).parseQuery()
                    .toSqlString(MysqlSqlDialect.DEFAULT).getSql();
            queries.add(sql + ";");
        }
        // Match the nested IF/AND used by the saved shared fields, including both period bounds.
        String period = DashboardFormulaSyntax.sql("if(and(d>=DATE_SUB(CAST('2026-10-05' AS DATE),7),"
                + "d<=DATE_SUB(CAST('2026-10-11' AS DATE),7)),amount,0)");
        queries.add(SqlParser.create("SELECT SUM(" + period + ") FROM (SELECT DATE '2026-09-27' d,100 amount "
                + "UNION ALL SELECT DATE '2026-09-28',20 UNION ALL SELECT DATE '2026-10-04',30 "
                + "UNION ALL SELECT DATE '2026-10-05',200) t").parseQuery()
                .toSqlString(MysqlSqlDialect.DEFAULT).getSql() + ";");
        if (args.length > 0) Files.write(Path.of(args[0]), queries);
        System.out.println("Dashboard date arithmetic: parameter binding, nested formulas, literals, native intervals and MySQL SQL generation passed");
    }
}
