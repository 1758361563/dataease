package io.dataease.company.bi.dataupdate;

import io.dataease.company.bi.etl.EtlConnections;
import io.dataease.datasource.manage.DataSourceManage;
import io.dataease.datasource.provider.CalciteProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.util.*;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;

@Component
@ConditionalOnProperty(name="company.bi.auth.enabled", havingValue="true", matchIfMissing=true)
public class UpdateConnections {
    private final DataSourceManage sources;
    private final CalciteProvider provider;
    public UpdateConnections(DataSourceManage sources, CalciteProvider provider) { this.sources = sources; this.provider = provider; }
    public String schema(String id) {
        try {
            var dto = sources.getDs(Long.parseLong(id));
            var config = new ObjectMapper().readTree(dto.getConfiguration());
            String field = "mysql".equals(dto.getType()) ? "dataBase" : "schema";
            // JDBC URL mode obtains the database from the connection, not a stale host-form field.
            if ("mysql".equals(dto.getType()) && "jdbcUrl".equalsIgnoreCase(config.path("urlType").asText())) return "";
            return config.path(field).asText("").strip();
        } catch (Exception e) { throw new IllegalArgumentException("无法读取数据源的默认 schema"); }
    }
    public EtlConnections.Handle open(String id) throws Exception {
        var dto = sources.getDs(Long.parseLong(id));
        require(dto != null && Set.of("mysql", "pg", "sqlServer").contains(dto.getType()), "数据更新支持 MySQL、PostgreSQL 和 SQL Server 数据源");
        EtlConnections.Handle handle = new EtlConnections.Handle(provider.getConnection(dto));
        try {
            String schema = schema(id);
            if ("pg".equals(dto.getType()) && !schema.isEmpty()) handle.connection().setSchema(schema);
            return handle;
        } catch (Exception e) { handle.close(); throw e; }
    }
    public enum Dialect {
        MYSQL, POSTGRES, SQLSERVER;
        public static Dialect of(Connection connection) throws SQLException {
            String name = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            if (name.contains("mysql")) return MYSQL;
            if (name.contains("postgresql")) return POSTGRES;
            if (name.contains("microsoft sql server")) return SQLSERVER;
            throw new IllegalArgumentException("执行连接仅支持 MySQL、PostgreSQL 和 SQL Server");
        }
        public String quote(String value) {
            UpdateDefinition.identifier(value);
            return switch (this) {
                case MYSQL -> "`" + value.replace("`", "``") + "`";
                case POSTGRES -> "\"" + value.replace("\"", "\"\"") + "\"";
                case SQLSERVER -> "[" + value.replace("]", "]]") + "]";
            };
        }
        public String table(UpdateDefinition task) { return task.schema().isEmpty() ? quote(task.table()) : quote(task.schema()) + "." + quote(task.table()); }
        public String stage(String name) { return quote(this == SQLSERVER ? "#" + name : name); }
        public String createStage(String stage, String target) {
            return createStage(stage, target, "*");
        }
        public String createStage(String stage, String target, String columns) {
            return this == SQLSERVER ? "SELECT TOP 0 " + columns + " INTO " + stage + " FROM " + target + " t CROSS JOIN (SELECT 1 AS __du_dummy) d"
                    : "CREATE TEMPORARY TABLE " + stage + " AS SELECT " + columns + " FROM " + target + " WHERE 1=0";
        }
    }
}
