package io.dataease.company.bi.etl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public final class EtlModel {
    private EtlModel() {}
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final ObjectMapper JSON = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    public record Source(String repo, String commit, String generatedAt) {}
    public record Baseline(String month, long rows, BigDecimal money, BigDecimal tolerance) {}
    public record Acceptance(String moneyField, String historyStart, Baseline baseline) {}
    public record Task(String etlName, String targetSchema, String targetTable, String windowField,
                       List<String> dependsOn, String sql, String sqlSha256, Acceptance acceptance) {}
    public record Init(String sql, String sqlSha256) {}
    public record Manifest(int schemaVersion, String packageKind, String runKind, Source source,
                           int windowDays, List<Task> tasks, List<Init> init) {}
    public record SqlPackage(long id, Manifest manifest, Map<String, String> files) {}
    public record Window(String start, String end) {
        public static Window at(Instant instant) {
            LocalDate end = instant.atZone(ZONE).toLocalDate();
            return new Window(end.minusDays(35).toString(), end.toString());
        }
    }
    public static String time(Instant value) { return TIME.format(value.atZone(ZONE)); }
    public static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("ETL 数据格式无效"); }
    }
    public static <T> T read(String value, Class<T> type) {
        try { return JSON.readValue(value, type); }
        catch (Exception e) { throw new IllegalArgumentException("ETL 清单格式无效"); }
    }
    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    /** Deliberately omit driver exception messages: they can contain credentials and row data. */
    public static String error(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                return "数据库操作失败，SQLState=" + (state != null && state.matches("[A-Za-z0-9]{5}") ? state : "unknown")
                        + "，错误码=" + sql.getErrorCode();
            }
        }
        return "操作失败，请检查数据源连接、SQL 包和运行状态";
    }
}
