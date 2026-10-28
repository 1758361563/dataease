package io.dataease.company.bi.etl;

import java.math.BigDecimal;
import java.sql.*;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import static io.dataease.company.bi.etl.EtlModel.*;

public final class EtlAcceptance {
    private EtlAcceptance() {}
    public record Metrics(long rows, long historyRows, BigDecimal historyMoney, long baselineRows,
                          BigDecimal baselineMoney, String min, String max, long windowRows) {}
    public static String quote(String identifier) { return "`" + identifier.replace("`", "``") + "`"; }
    public static Metrics measure(Connection connection, Task task, Window window) throws SQLException {
        String field = quote(task.windowField()), money = quote(task.acceptance().moneyField());
        YearMonth month = YearMonth.parse(task.acceptance().baseline().month());
        String inMonth = field + ">=? AND " + field + "<?";
        String sql = "SELECT COUNT(*),SUM(" + field + "<?),SUM(CASE WHEN " + field + "<? THEN " + money
                + " ELSE 0 END),SUM(" + inMonth + "),SUM(CASE WHEN " + inMonth + " THEN " + money
                + " ELSE 0 END),DATE_FORMAT(MIN(" + field + "),'%Y-%m-%d'),DATE_FORMAT(MAX(" + field
                + "),'%Y-%m-%d'),SUM(" + field + ">=? AND " + field + "<?) FROM "
                + quote(task.targetSchema()) + "." + quote(task.targetTable());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            String[] args = {window.start(), window.start(), month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString(),
                    month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString(), window.start(), window.end()};
            for (int i = 0; i < args.length; i++) statement.setString(i + 1, args[i]);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return new Metrics(row.getLong(1), row.getLong(2), decimal(row, 3), row.getLong(4),
                        decimal(row, 5), row.getString(6), row.getString(7), row.getLong(8));
            }
        }
    }
    private static BigDecimal decimal(ResultSet row, int column) throws SQLException {
        BigDecimal number = row.getBigDecimal(column);
        return number == null ? BigDecimal.ZERO : number;
    }
    public static List<String> issues(Metrics before, Metrics after, Task task, Window window) {
        List<String> issues = new ArrayList<>();
        if (before.historyRows != after.historyRows || before.historyMoney.compareTo(after.historyMoney) != 0) issues.add("窗口外行数或金额变化");
        if (before.baselineRows != after.baselineRows || before.baselineMoney.compareTo(after.baselineMoney) != 0) issues.add("基线月份切片变化");
        Baseline baseline = task.acceptance().baseline();
        if (after.baselineRows != baseline.rows() || after.baselineMoney.subtract(baseline.money()).abs().compareTo(baseline.tolerance()) > 0)
            issues.add("与对账基线不一致");
        if (after.windowRows <= 0) issues.add("刷新窗口内没有行");
        if (!task.acceptance().historyStart().equals(after.min) || after.max == null || after.max.compareTo(window.end()) >= 0)
            issues.add("覆盖日期异常");
        return issues;
    }
}
