package io.dataease.company.bi.etl;

import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import static io.dataease.company.bi.etl.EtlModel.*;

public final class EtlSqlScript {
    private EtlSqlScript() {}
    public static List<String> statements(String sql) {
        String text = String.join("\n", sql.lines().filter(line -> !line.strip().startsWith("--")).toList());
        return Arrays.stream(text.split(";", -1)).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
    public static String render(String sql, Window window) {
        String result = sql.replace("__REQUIRED_WINDOW_START__", window.start())
                .replace("__REQUIRED_WINDOW_END__", window.end()).replace("__SAFETY_SMOKE_PASSED__", "YES");
        require(!result.contains("__REQUIRED_") && !result.contains("__SAFETY_SMOKE_PASSED__"), "SQL 占位符未替换");
        validate(result);
        return result;
    }
    public static void validate(String sql) {
        require(!sql.toUpperCase(Locale.ROOT).contains("RENAME TABLE"), "刷新 SQL 禁止 RENAME TABLE");
        boolean transaction = false;
        for (String statement : statements(sql)) {
            String upper = statement.toUpperCase(Locale.ROOT).stripLeading();
            require(!upper.startsWith("RENAME"), "刷新 SQL 禁止 RENAME");
            if (statement.contains("__STOP__")) continue;
            // Strip leading block comments only for the additional DDL guard; execution keeps the original text.
            String ddl = upper.replaceFirst("(?s)^(?:/\\*.*?\\*/\\s*)+", "");
            require(!transaction || !ddl.matches("(?s)^(CREATE|DROP|ALTER|TRUNCATE|RENAME)\\b.*"), "事务内禁止 DDL");
            if (upper.startsWith("START TRANSACTION")) transaction = true;
            else if (upper.startsWith("COMMIT") || upper.startsWith("ROLLBACK")) transaction = false;
        }
        require(!transaction, "SQL 事务缺少 COMMIT 或 ROLLBACK");
    }
    public static void prepare(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String name : List.of("net_read_timeout", "net_write_timeout", "wait_timeout", "interactive_timeout"))
                statement.execute("SET SESSION " + name + "=28800");
        }
        connection.commit();
    }
    public static void execute(Connection connection, String sql, Runnable checkLock, Consumer<Statement> active) throws SQLException {
        validate(sql);
        boolean transaction = false;
        try (Statement statement = connection.createStatement()) {
            active.accept(statement);
            for (String part : statements(sql)) {
                if (part.contains("__STOP__")) continue;
                checkLock.run();
                if (Thread.currentThread().isInterrupted()) throw new SQLException("Interrupted", "HY008");
                boolean results = statement.execute(part);
                while (true) {
                    if (results) { try (ResultSet rows = statement.getResultSet()) { while (rows.next()) { /* consume */ } } }
                    else if (statement.getUpdateCount() == -1) break;
                    results = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                }
                String upper = part.toUpperCase(Locale.ROOT);
                if (upper.startsWith("START TRANSACTION")) transaction = true;
                else if (upper.startsWith("COMMIT") || upper.startsWith("ROLLBACK")) transaction = false;
                else if (!transaction) connection.commit();
            }
            checkLock.run();
        } catch (Exception e) {
            rollback(connection);
            if (e instanceof SQLException sqlError) throw sqlError;
            throw e;
        } finally { active.accept(null); }
    }
    public static void rollback(Connection connection) {
        try (Statement s = connection.createStatement()) { s.execute("ROLLBACK"); } catch (SQLException ignored) { }
        try { connection.rollback(); } catch (SQLException ignored) { }
    }
}
