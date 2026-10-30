package io.dataease.company.bi.dataupdate;

import org.quartz.CronExpression;
import java.time.*;
import java.util.*;

/** A saved definition is immutable for the duration of a run. IDs stay strings over HTTP. */
public record UpdateDefinition(
        String id, long version, String datasourceId, String schema, String table,
        Mode mode, String sourceDatasourceId, String sql, Strategy strategy,
        List<String> keys, String windowColumn, int windowDays, boolean allowEmpty,
        Trigger trigger, boolean enabled, String cron, String timezone,
        List<String> dependencies, int timeoutSeconds, long maxRows, String owner,
        String structureFingerprint, long minimumRows) {
    public UpdateDefinition(String id, long version, String datasourceId, String schema, String table,
            Mode mode, String sourceDatasourceId, String sql, Strategy strategy, List<String> keys,
            String windowColumn, int windowDays, boolean allowEmpty, Trigger trigger, boolean enabled,
            String cron, String timezone, List<String> dependencies, int timeoutSeconds, long maxRows, String owner) {
        this(id,version,datasourceId,schema,table,mode,sourceDatasourceId,sql,strategy,keys,windowColumn,
                windowDays,allowEmpty,trigger,enabled,cron,timezone,dependencies,timeoutSeconds,maxRows,owner,"",0);
    }
    public enum Mode { SCRIPT, QUERY }
    public enum Strategy { FULL, WINDOW, UPSERT }
    public enum Trigger { MANUAL, CRON, DEPENDENCY }

    public UpdateDefinition withSchema(String value) {
        return new UpdateDefinition(id, version, datasourceId, value, table, mode, sourceDatasourceId, sql, strategy,
                keys, windowColumn, windowDays, allowEmpty, trigger, enabled, cron, timezone, dependencies, timeoutSeconds, maxRows, owner,structureFingerprint,minimumRows);
    }

    public UpdateDefinition normalized(String taskId, long revision, String instance) {
        return new UpdateDefinition(taskId, revision, datasourceId, clean(schema), clean(table), mode,
                mode == Mode.QUERY ? sourceDatasourceId : datasourceId, clean(sql), strategy,
                keys == null ? List.of() : keys.stream().map(String::strip).toList(), clean(windowColumn),
                windowDays, allowEmpty, trigger, enabled, clean(cron), clean(timezone),
                dependencies == null ? List.of() : List.copyOf(dependencies), timeoutSeconds, maxRows, instance,clean(structureFingerprint),minimumRows);
    }

    public void validate() {
        require(datasourceId != null && datasourceId.matches("[0-9]+"), "请选择目标数据源");
        identifier(table); if (!schema.isEmpty()) identifier(schema);
        require(mode != null && strategy != null && trigger != null, "请选择执行方式、更新策略和触发方式");
        require(!sql.isBlank() && sql.length() <= 2_000_000, "SQL 不能为空且不能超过 200 万字符");
        require(windowDays >= 1 && windowDays <= 3660, "更新窗口须为 1–3660 天");
        require(timeoutSeconds >= 1 && timeoutSeconds <= 86400, "执行超时须为 1–86400 秒");
        require(maxRows >= 1 && maxRows <= 100_000_000, "抽取行数上限须为 1–100000000");
        require(minimumRows >= 0 && minimumRows <= maxRows, "最小行数必须在 0 与抽取上限之间");
        try { ZoneId.of(timezone); } catch (Exception e) { throw new IllegalArgumentException("时区无效"); }
        require(new HashSet<>(keys).size() == keys.size(), "主键不能重复");
        keys.forEach(UpdateDefinition::identifier);
        require(new HashSet<>(dependencies).size() == dependencies.size() && (id == null || !dependencies.contains(id)), "上游任务重复或引用了自身");
        require(dependencies.size() <= 64, "上游任务不能超过 64 个");
        if (trigger == Trigger.CRON) {
            require(CronExpression.isValidExpression(cron), "Quartz Cron 表达式无效");
            try {
                CronExpression expression = new CronExpression(cron);
                expression.setTimeZone(TimeZone.getTimeZone(ZoneId.of(timezone)));
                require(expression.getNextValidTimeAfter(new Date()) != null, "Cron 表达式没有未来执行时间");
            } catch (java.text.ParseException e) { throw new IllegalArgumentException("Quartz Cron 表达式无效"); }
        }
        if (trigger == Trigger.DEPENDENCY) require(!dependencies.isEmpty(), "请选择上游任务");
        if (mode == Mode.QUERY) {
            require(sourceDatasourceId != null && sourceDatasourceId.matches("[0-9]+"), "请选择查询数据源");
            if (strategy == Strategy.UPSERT) require(!keys.isEmpty(), "按主键更新需要配置主键");
            if (strategy == Strategy.WINDOW) identifier(windowColumn);
            UpdateSql.requireQuery(sql);
        } else UpdateSql.statements(sql);
    }

    public LocalDate businessDate(Instant planned) { return planned.atZone(ZoneId.of(timezone)).toLocalDate(); }
    public static void identifier(String value) {
        require(value != null && !value.isBlank() && value.length() <= 128 && value.chars().noneMatch(Character::isISOControl), "表名或字段名无效");
    }
    public static String clean(String value) { return value == null ? "" : value.strip(); }
    public static final class ValidationException extends IllegalArgumentException {
        public ValidationException(String message) { super(message); }
    }
    public static void require(boolean condition, String message) { if (!condition) throw new ValidationException(message); }

    public static void validateGraph(Map<String, UpdateDefinition> tasks) {
        Set<String> visited = new HashSet<>(), visiting = new HashSet<>();
        for (String id : tasks.keySet()) visit(id, tasks, visited, visiting);
    }
    private static void visit(String id, Map<String, UpdateDefinition> tasks, Set<String> visited, Set<String> visiting) {
        if (visited.contains(id)) return;
        require(visiting.add(id), "上游依赖形成循环");
        UpdateDefinition task = tasks.get(id);
        require(task != null, "上游任务不存在");
        for (String upstream : task.dependencies) visit(upstream, tasks, visited, visiting);
        visiting.remove(id); visited.add(id);
    }
}
