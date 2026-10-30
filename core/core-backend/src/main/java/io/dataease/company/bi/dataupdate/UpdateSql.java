package io.dataease.company.bi.dataupdate;

import java.time.LocalDate;
import java.util.*;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;

/** Splits JDBC batches without splitting string literals, quoted identifiers or comments. */
public final class UpdateSql {
    private UpdateSql() { }
    public static List<String> statements(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        char quote = 0; boolean line = false; int block = 0; String dollar = null;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i), next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (line) { if (c == '\n' || c == '\r') { line = false; part.append(' '); } continue; }
            if (block > 0) {
                if (c == '/' && next == '*') { block++; i++; }
                else if (c == '*' && next == '/') { block--; i++; part.append(' '); }
                continue;
            }
            if (dollar != null) {
                if (sql.startsWith(dollar, i)) { part.append(dollar); i += dollar.length() - 1; dollar = null; }
                else part.append(c);
                continue;
            }
            if (quote != 0) {
                part.append(c);
                if (c == '\\' && quote == '\'') throw new IllegalArgumentException("字符串请用双单引号转义，不支持反斜杠转义");
                if (c == quote) { if (next == quote) { part.append(next); i++; } else quote = 0; }
                continue;
            }
            if (c == '-' && next == '-') {
                require(i + 2 == sql.length() || Character.isWhitespace(sql.charAt(i + 2)), "-- 注释标记后请加空格");
                line = true; i++; part.append(' '); continue;
            }
            if (c == '/' && next == '*') {
                require(i + 2 >= sql.length() || sql.charAt(i + 2) != '!', "请将 MySQL 可执行注释展开为普通 SQL");
                block++; i++; part.append(' '); continue;
            }
            if (c == '\'' || c == '"' || c == '`' || c == '[') { quote = c == '[' ? ']' : c; part.append(c); continue; }
            require(c != '#', "注释请使用 -- 或 /* */；SQL Server 临时表名请写成 [#表名]");
            if (c == '$') {
                var matcher = java.util.regex.Pattern.compile("\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$").matcher(sql.substring(i));
                if (matcher.lookingAt()) { dollar = matcher.group(); part.append(dollar); i += dollar.length() - 1; continue; }
            }
            if (c == ';') { add(result, part); continue; }
            part.append(c);
        }
        require(quote == 0 && block == 0 && dollar == null, "SQL 包含未闭合的字符串、标识符或注释");
        add(result, part);
        require(!result.isEmpty(), "SQL 不能为空");
        require(result.size() <= 1000, "单次脚本不能超过 1000 条语句");
        for (String statement : result) {
            require(!java.util.regex.Pattern.compile("(?im)^\\s*GO\\s*$").matcher(statement).find() && !statement.matches("(?is)^\\s*DELIMITER\\b.*"), "请使用分号分隔 JDBC 语句，不支持 GO / DELIMITER");
            require(!statement.matches("(?is)^\\s*(BEGIN|START\\s+TRANSACTION|COMMIT|ROLLBACK|SAVEPOINT|SET\\s+AUTOCOMMIT)\\b.*"), "事务由执行器管理，请移除事务控制语句");
        }
        return result;
    }
    private static void add(List<String> result, StringBuilder part) {
        String value = part.toString().strip(); if (!value.isEmpty()) result.add(value); part.setLength(0);
    }
    public static String requireQuery(String sql) {
        List<String> parts = statements(sql);
        require(parts.size() == 1, "查询抽取只接受一条 SELECT 查询");
        // A conservative query grammar: CTEs and subqueries are accepted by the database;
        // write-capable tokens outside literals are rejected even inside CTEs.
        String words = outsideLiterals(parts.getFirst()).toUpperCase(Locale.ROOT);
        require(!words.contains("$") && !words.contains("#"), "查询中的字符串请用单引号，标识符请使用数据库的引号语法");
        require(words.stripLeading().matches("(?s)^(SELECT|WITH)\\b.*"), "查询抽取只接受 SELECT 或 WITH 查询");
        require(!java.util.regex.Pattern.compile("\\b(INSERT|UPDATE|DELETE|MERGE|INTO|COPY|CALL|EXEC|EXECUTE|CREATE|ALTER|DROP|TRUNCATE|GRANT|REVOKE|FOR\\s+UPDATE|LOCK|OUTFILE)\\b").matcher(words).find(), "查询中包含写入或锁定操作，请使用完整脚本模式");
        return parts.getFirst();
    }
    static String outsideLiterals(String sql) {
        StringBuilder result = new StringBuilder(); char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) { if (c == quote) { if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) i++; else quote = 0; } result.append(' '); }
            else if (c == '\'' || c == '"' || c == '`' || c == '[') { quote = c == '[' ? ']' : c; result.append(' '); }
            else result.append(c);
        }
        return result.toString();
    }
    public static String render(String sql, LocalDate date, int days, String target) {
        String rendered = sql.replace("{{window_start}}", "'" + date.minusDays(days) + " 00:00:00'")
                .replace("{{window_end}}", "'" + date + " 00:00:00'").replace("{{target_table}}", target);
        require(!rendered.contains("{{") && !rendered.contains("}}"), "SQL 中存在未知参数");
        return rendered;
    }
}
