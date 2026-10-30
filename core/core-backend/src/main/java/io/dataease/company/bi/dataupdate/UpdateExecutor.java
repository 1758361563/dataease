package io.dataease.company.bi.dataupdate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;
import static io.dataease.company.bi.dataupdate.UpdateConnections.Dialect;

@Component
@ConditionalOnProperty(name="company.bi.auth.enabled", havingValue="true", matchIfMissing=true)
public class UpdateExecutor {
    private final UpdateConnections connections;
    public UpdateExecutor(UpdateConnections connections) { this.connections = connections; }

    public long execute(UpdateDefinition task, LocalDate date, UpdateExecution execution) throws Exception {
        require(Objects.equals(UpdateDefinition.clean(connections.schema(task.datasourceId())),task.schema()),"数据源 schema 已变化，请核查并重新配置目标表");
        try (var handle = connections.open(task.datasourceId())) {
            Connection target = handle.connection(); Dialect dialect = Dialect.of(target);
            String table = dialect.table(task);
            if (!UpdateDefinition.clean(task.structureFingerprint()).isEmpty()) {
                require(task.structureFingerprint().equals(UpdateInspection.structure(target,task.schema(),task.table()).fingerprint()),"目标表结构已变化，请重新检查字段并保存配置");
            }
            target.setAutoCommit(false);
            try {
                long count = task.mode() == UpdateDefinition.Mode.SCRIPT
                        ? script(target, UpdateSql.render(task.sql(), date, task.windowDays(), table), execution)
                        : extract(task, date, target, dialect, table, execution);
                execution.beforeCommit(); target.commit(); return count;
            } catch (Exception e) {
                try { target.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }
    }

    private long script(Connection connection, String sql, UpdateExecution execution) throws SQLException {
        long rows = 0;
        for (String part : UpdateSql.statements(sql)) {
            Statement statement = execution.watch(connection.createStatement());
            try (statement) {
                boolean result = statement.execute(part);
                while (true) {
                    execution.check();
                    if (!result && statement.getUpdateCount() == -1) break;
                    if (!result) rows += Math.max(0, statement.getUpdateCount());
                    result = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                }
            } finally { execution.forget(statement); }
        }
        return rows;
    }

    private long extract(UpdateDefinition task, LocalDate date, Connection target, Dialect dialect, String table, UpdateExecution execution) throws Exception {
        require(target.getMetaData().supportsTransactions(), "目标数据库需要支持事务");
        if (dialect == Dialect.MYSQL) {
            command(target,"SET SESSION sql_mode = CONCAT_WS(',', @@sql_mode, 'STRICT_ALL_TABLES')",execution);
            try (PreparedStatement ps = target.prepareStatement("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
                ps.setString(1, task.schema().isEmpty() ? target.getCatalog() : task.schema()); ps.setString(2, task.table());
                try (ResultSet rs = ps.executeQuery()) { require(rs.next() && "InnoDB".equalsIgnoreCase(rs.getString(1)), "查询抽取的 MySQL 目标必须为 InnoDB 实表"); }
            }
        }
        String stage = dialect.stage("du_" + UUID.randomUUID().toString().replace("-", ""));
        {
            TargetColumns targetShape = columns(target, table, execution);
            List<String> targetColumns = targetShape.names();
            List<String> columns;
            long count = 0;
            try (var sourceHandle = connections.open(task.sourceDatasourceId())) {
                Connection source = sourceHandle.connection(); Dialect sourceDialect = Dialect.of(source);
                source.setReadOnly(true); source.setAutoCommit(false);
                String sql = UpdateSql.requireQuery(UpdateSql.render(task.sql(), date, task.windowDays(), table));
                Statement query = execution.watch(source.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
                try (query) {
                    query.setFetchSize(sourceDialect == Dialect.MYSQL ? Integer.MIN_VALUE : 500);
                    try (ResultSet rs = query.executeQuery(sql)) {
                        columns = labels(rs.getMetaData());
                        require(targetColumns.containsAll(columns), "查询列名须与目标字段完全一致，请用 AS 设置别名");
                        List<String> incompatibilities=new ArrayList<>();
                        UpdateInspection.compare(UpdateInspection.fields(rs.getMetaData()),targetShape.fields().stream().filter(field->columns.contains(field.name())).toList(),task,incompatibilities,new ArrayList<>());
                        require(incompatibilities.isEmpty(),String.join("；",incompatibilities));
                        require(columns.containsAll(task.keys()), "查询结果缺少配置的主键字段");
                        if (task.strategy() == UpdateDefinition.Strategy.WINDOW) require(columns.contains(task.windowColumn()), "查询结果缺少窗口字段");
                        String names = join(columns, dialect);
                        String stageColumns = dialect == Dialect.SQLSERVER
                                ? columns.stream().map(column -> "t." + dialect.quote(column)).collect(Collectors.joining(",")) : names;
                        command(target, dialect.createStage(stage, table, stageColumns), execution);
                        PreparedStatement insert = execution.watch(target.prepareStatement("INSERT INTO " + stage + " (" + names + ") VALUES (" + String.join(",", Collections.nCopies(columns.size(), "?")) + ")"));
                        try (insert) {
                            while (rs.next()) {
                                execution.check(); require(++count <= task.maxRows(), "查询结果超过抽取行数上限");
                                for (int i = 1; i <= columns.size(); i++) {
                                    Object value = rs.getObject(i);
                                    // LOB handles belong to the source connection; materialize before batching.
                                    if (value instanceof Clob clob) { require(clob.length() <= 16_777_216, "单字段超过 16 MB"); value = clob.getSubString(1, (int)clob.length()); clob.free(); }
                                    if (value instanceof Blob blob) { require(blob.length() <= 16_777_216, "单字段超过 16 MB"); value = blob.getBytes(1, (int)blob.length()); blob.free(); }
                                    insert.setObject(i, value);
                                }
                                insert.addBatch(); if (count % 500 == 0) { insert.executeBatch(); insert.clearBatch(); }
                            }
                            if (count % 500 != 0) insert.executeBatch();
                        } finally { execution.forget(insert); }
                    }
                } finally { execution.forget(query); source.rollback(); }
            }
            require(count > 0 || task.allowEmpty(), "抽取结果为空，目标表未更新；如确需清空可启用允许空结果");
            require(count >= task.minimumRows(), "抽取结果少于配置的最小行数，目标表未更新");
            if (!task.keys().isEmpty()) validateKeys(target, stage, task.keys(), dialect, execution);
            String names = join(columns, dialect);
            Set<String> suppliedIdentity = new HashSet<>(targetShape.identities()); suppliedIdentity.retainAll(columns);
            if (task.strategy() == UpdateDefinition.Strategy.UPSERT && dialect != Dialect.MYSQL)
                require(task.keys().containsAll(suppliedIdentity), "按主键更新时，查询包含的 identity 字段须作为匹配主键");
            String insertPrefix = "INSERT INTO " + table + " (" + names + ")"
                    + (dialect == Dialect.POSTGRES && !suppliedIdentity.isEmpty() ? " OVERRIDING SYSTEM VALUE" : "");
            boolean identityInsert = dialect == Dialect.SQLSERVER && !suppliedIdentity.isEmpty();
            if (identityInsert) command(target, "SET IDENTITY_INSERT " + table + " ON", execution);
            try {
            switch (task.strategy()) {
                case FULL -> {
                    command(target, "DELETE FROM " + table, execution);
                    command(target, insertPrefix + " SELECT " + names + " FROM " + stage, execution);
                }
                case WINDOW -> {
                    String field = dialect.quote(task.windowColumn());
                    String start = "'" + date.minusDays(task.windowDays()) + " 00:00:00'", end = "'" + date + " 00:00:00'";
                    require(!exists(target, "SELECT 1 FROM " + stage + " WHERE " + field + " IS NULL OR " + field + " < " + start + " OR " + field + " >= " + end, execution), "查询结果包含窗口之外的数据或空窗口值");
                    command(target, "DELETE FROM " + table + " WHERE " + field + ">=" + start + " AND " + field + "<" + end, execution);
                    command(target, insertPrefix + " SELECT " + names + " FROM " + stage, execution);
                }
                case UPSERT -> upsert(target, dialect, table, stage, columns, task.keys(), insertPrefix, execution);
            }
            } finally {
                if (identityInsert) command(target, "SET IDENTITY_INSERT " + table + " OFF", execution);
            }
            return count;
        }
        // The provider creates a dedicated JDBC connection. Its close removes the
        // session-local stage after commit/rollback, without cleanup DDL affecting either.
    }
    private void upsert(Connection target, Dialect dialect, String table, String stage, List<String> columns, List<String> keys, String insertPrefix, UpdateExecution execution) throws SQLException {
        String on = keys.stream().map(key -> "t." + dialect.quote(key) + "=s." + dialect.quote(key)).collect(Collectors.joining(" AND "));
        require(!exists(target, "SELECT " + keys.stream().map(key -> "t." + dialect.quote(key)).collect(Collectors.joining(",")) + " FROM " + table + " t JOIN " + stage + " s ON " + on
                + " GROUP BY " + keys.stream().map(key -> "t." + dialect.quote(key)).collect(Collectors.joining(",")) + " HAVING COUNT(*)>1", execution), "目标表存在重复主键");
        List<String> values = columns.stream().filter(column -> !keys.contains(column)).toList();
        if (!values.isEmpty()) {
            String set = values.stream().map(column -> (dialect == Dialect.MYSQL ? "t." : "") + dialect.quote(column) + "=s." + dialect.quote(column)).collect(Collectors.joining(","));
            String update = switch (dialect) {
                case MYSQL -> "UPDATE " + table + " t JOIN " + stage + " s ON " + on + " SET " + set;
                case POSTGRES -> "UPDATE " + table + " t SET " + set + " FROM " + stage + " s WHERE " + on;
                case SQLSERVER -> "UPDATE t SET " + set + " FROM " + table + " t JOIN " + stage + " s ON " + on;
            };
            command(target, update, execution);
        }
        command(target, insertPrefix + " SELECT " + columns.stream().map(column -> "s." + dialect.quote(column)).collect(Collectors.joining(","))
                + " FROM " + stage + " s WHERE NOT EXISTS (SELECT 1 FROM " + table + " t WHERE " + on + ")", execution);
    }
    private void validateKeys(Connection connection, String stage, List<String> keys, Dialect dialect, UpdateExecution execution) throws SQLException {
        require(!exists(connection, "SELECT 1 FROM " + stage + " WHERE " + keys.stream().map(key -> dialect.quote(key) + " IS NULL").collect(Collectors.joining(" OR ")), execution), "抽取结果主键包含空值");
        String names = join(keys, dialect);
        require(!exists(connection, "SELECT " + names + " FROM " + stage + " GROUP BY " + names + " HAVING COUNT(*)>1", execution), "抽取结果主键重复，请在 SQL 中明确去重规则");
    }
    static List<String> labels(ResultSetMetaData metadata) throws SQLException {
        List<String> result = new ArrayList<>();
        for (int i=1; i<=metadata.getColumnCount(); i++) { String name = metadata.getColumnLabel(i); UpdateDefinition.identifier(name); require(!result.contains(name), "查询结果包含重复列名"); result.add(name); }
        require(!result.isEmpty(), "查询结果没有字段"); return result;
    }
    private record TargetColumns(List<String> names, Set<String> identities,List<UpdateInspection.Column> fields) { }
    private TargetColumns columns(Connection connection, String table, UpdateExecution execution) throws SQLException {
        Statement statement = execution.watch(connection.createStatement());
        try (statement; ResultSet rs = statement.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            ResultSetMetaData meta = rs.getMetaData(); Set<String> identities = new HashSet<>();
            for (int i=1; i<=meta.getColumnCount(); i++) if (meta.isAutoIncrement(i)) identities.add(meta.getColumnLabel(i));
            return new TargetColumns(labels(meta), identities,UpdateInspection.fields(meta));
        }
        finally { execution.forget(statement); }
    }
    private static String join(List<String> columns, Dialect dialect) { return columns.stream().map(dialect::quote).collect(Collectors.joining(",")); }
    private boolean exists(Connection connection, String sql, UpdateExecution execution) throws SQLException {
        Statement statement = execution.watch(connection.createStatement());
        try (statement) { statement.setMaxRows(1); try (ResultSet rs = statement.executeQuery(sql)) { return rs.next(); } }
        finally { execution.forget(statement); }
    }
    private void command(Connection connection, String sql, UpdateExecution execution) throws SQLException {
        Statement statement = execution.watch(connection.createStatement());
        try (statement) { execution.check(); statement.executeUpdate(sql); }
        finally { execution.forget(statement); }
    }
}
