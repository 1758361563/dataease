package io.dataease.company.bi.dataupdate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.time.*;
import java.util.*;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;

@Component
@ConditionalOnProperty(name="company.bi.auth.enabled",havingValue="true",matchIfMissing=true)
public class UpdateInspection {
    public record Column(String name,int jdbcType,String type,int precision,int scale,boolean nullable,String defaultValue,boolean generated) { }
    public record Index(String name,boolean unique,String column,int position) { }
    public record Structure(List<Column> columns,List<Index> indexes,String fingerprint) { }
    public record Report(Structure target,List<Column> source,List<String> errors,List<String> warnings,List<List<Object>> sample) { }
    static List<Column> fields(ResultSetMetaData metadata)throws SQLException {
        List<Column> columns=new ArrayList<>();
        for(int i=1;i<=metadata.getColumnCount();i++)columns.add(new Column(metadata.getColumnLabel(i),metadata.getColumnType(i),metadata.getColumnTypeName(i),metadata.getPrecision(i),metadata.getScale(i),metadata.isNullable(i)!=ResultSetMetaData.columnNoNulls,null,metadata.isAutoIncrement(i)));
        return columns;
    }
    private final UpdateConnections connections;
    public UpdateInspection(UpdateConnections connections) { this.connections=connections; }
    public Structure structure(String datasource,String table) throws Exception {
        try(var handle=connections.open(datasource)) { return structure(handle.connection(),connections.schema(datasource),table); }
    }
    public static Structure structure(Connection connection,String schema,String table) throws SQLException {
        UpdateDefinition.identifier(table);
        var dialect=UpdateConnections.Dialect.of(connection);
        String catalog=dialect==UpdateConnections.Dialect.MYSQL && !schema.isEmpty() ? schema : connection.getCatalog();
        String namespace=dialect==UpdateConnections.Dialect.MYSQL ? null : schema.isEmpty() ? connection.getSchema() : schema;
        DatabaseMetaData meta=connection.getMetaData(); String escape=Objects.toString(meta.getSearchStringEscape(),"\\");
        String pattern=table.replace(escape,escape+escape).replace("%",escape+"%").replace("_",escape+"_");
        List<Column> columns=new ArrayList<>();
        try(ResultSet rs=meta.getColumns(catalog,namespace,pattern,null)) {
            while(rs.next()) {
                if(!table.equals(rs.getString("TABLE_NAME"))) continue;
                boolean generated="YES".equals(rs.getString("IS_AUTOINCREMENT"));
                try { generated|="YES".equals(rs.getString("IS_GENERATEDCOLUMN")); } catch(SQLException ignored) { }
                columns.add(new Column(rs.getString("COLUMN_NAME"),rs.getInt("DATA_TYPE"),rs.getString("TYPE_NAME"),rs.getInt("COLUMN_SIZE"),rs.getInt("DECIMAL_DIGITS"),rs.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls,rs.getString("COLUMN_DEF"),generated));
            }
        }
        require(!columns.isEmpty(),"目标表不存在或无法读取结构");
        List<Index> indexes=new ArrayList<>();
        try(ResultSet rs=meta.getIndexInfo(catalog,namespace,table,false,false)) {
            while(rs.next()) if(rs.getString("INDEX_NAME")!=null) indexes.add(new Index(rs.getString("INDEX_NAME"),!rs.getBoolean("NON_UNIQUE"),rs.getString("COLUMN_NAME"),rs.getInt("ORDINAL_POSITION")));
        }
        indexes.sort(Comparator.comparing(Index::name).thenComparingInt(Index::position));
        return new Structure(columns,indexes,UpdateStore.hash(UpdateStore.json(List.of(columns,indexes))));
    }
    public Report inspect(UpdateDefinition incoming,boolean preview) throws Exception {
        UpdateDefinition task=incoming.normalized(incoming.id(),incoming.version(),incoming.owner()).withSchema(connections.schema(incoming.datasourceId()));
        task.validate(); require(task.mode()==UpdateDefinition.Mode.QUERY,"字段检查适用于查询抽取");
        Structure target=structure(task.datasourceId(),task.table());
        try(var handle=connections.open(task.sourceDatasourceId())) {
            Connection source=handle.connection(); source.setReadOnly(true); source.setAutoCommit(false);
            try(Statement query=source.createStatement()) {
                query.setQueryTimeout(15); query.setMaxRows(preview?20:1); query.setFetchSize(1);
                String sql=UpdateSql.requireQuery(UpdateSql.render(task.sql(),task.businessDate(Instant.now()),task.windowDays(),UpdateConnections.Dialect.of(source).quote(task.table())));
                try(ResultSet rs=query.executeQuery(sql)) {
                    ResultSetMetaData meta=rs.getMetaData(); List<Column> fields=new ArrayList<>();
                    require(meta.getColumnCount()<=256,"检查与预览最多支持 256 个查询字段，请减少字段");
                    for(int i=1;i<=meta.getColumnCount();i++) fields.add(new Column(meta.getColumnLabel(i),meta.getColumnType(i),meta.getColumnTypeName(i),meta.getPrecision(i),meta.getScale(i),meta.isNullable(i)!=ResultSetMetaData.columnNoNulls,null,false));
                    List<String> errors=new ArrayList<>(),warnings=new ArrayList<>(); compare(fields,target.columns(),task,errors,warnings);
                    List<List<Object>> rows=new ArrayList<>(); int size=0;
                    while(preview && rows.size()<20 && rs.next()) {
                        List<Object> row=new ArrayList<>();
                        for(int i=1;i<=fields.size();i++) {
                            Object value;
                            int type=fields.get(i-1).jdbcType();
                            if(Set.of(Types.BLOB,Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.CLOB,Types.NCLOB,Types.LONGVARCHAR,Types.LONGNVARCHAR,Types.SQLXML,Types.OTHER,Types.ARRAY,Types.JAVA_OBJECT,Types.STRUCT).contains(type)) value="[大字段或复杂类型省略]";
                            else if(text(type)) {
                                try(var reader=rs.getCharacterStream(i)) {
                                    if(reader==null)value=null;
                                    else {StringBuilder text=new StringBuilder();char[] buffer=new char[128];int length;while(text.length()<512&&(length=reader.read(buffer,0,Math.min(buffer.length,512-text.length())))!=-1)text.append(buffer,0,length);value=text.toString();}
                                }
                            } else { String string=rs.getString(i); value=string==null?null:string.substring(0,Math.min(512,string.length())); }
                            size+=value==null?4:value.toString().length()*6;
                            require(size<=131072,"预览响应超过 128 KB，请减少查询字段"); row.add(value);
                        }
                        rows.add(row);
                    }
                    Report report=new Report(target,fields,errors,warnings,rows);
                    require(UpdateStore.json(report).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=131072,"检查响应超过 128 KB，请减少查询字段");
                    return report;
                }
            } finally { source.rollback(); }
        }
    }
    static void compare(List<Column> source,List<Column> target,UpdateDefinition task,List<String> errors,List<String> warnings) {
        Set<String> seen=new HashSet<>(); Map<String,Column> destinations=new HashMap<>(); target.forEach(column->destinations.put(column.name(),column));
        for(Column column:source) {
            if(!seen.add(column.name())) errors.add("查询列名重复："+column.name());
            Column destination=destinations.get(column.name());
            if(destination==null) { errors.add("目标缺少字段："+column.name()); continue; }
            if(column.nullable()&&!destination.nullable()) warnings.add(column.name()+"：源字段可能为空，运行时将校验目标约束");
            if(text(column.jdbcType())&&text(destination.jdbcType())&&destination.precision()>0&&column.precision()>destination.precision()) errors.add(column.name()+"：源字符串长度超过目标容量");
            if(decimal(column.jdbcType())&&decimal(destination.jdbcType()) && (column.scale()>destination.scale() || column.precision()-column.scale()>destination.precision()-destination.scale())) errors.add(column.name()+"：目标 decimal 精度不足");
            if(column.jdbcType()!=destination.jdbcType()) warnings.add(column.name()+"："+column.type()+" → "+destination.type()+"，请确认类型转换");
            if(text(column.jdbcType())&&(numeric(destination.jdbcType())||temporal(destination.jdbcType()))) errors.add(column.name()+"：请在 SELECT 中显式转换为目标数值或日期类型");
            if(temporal(column.jdbcType())&&numeric(destination.jdbcType())||numeric(column.jdbcType())&&temporal(destination.jdbcType())) errors.add(column.name()+"：数值与日期类型不兼容");
            if(destination.generated()) warnings.add(column.name()+"：目标为自动生成字段，请确认显式写入规则");
        }
        for(Column column:target) if(!seen.contains(column.name())&&!column.nullable()&&column.defaultValue()==null&&!column.generated()) errors.add("缺少必填目标字段："+column.name());
        for(String key:task.keys()) if(!seen.contains(key)) errors.add("缺少主键字段："+key);
        if(task.strategy()==UpdateDefinition.Strategy.WINDOW&&!seen.contains(task.windowColumn())) errors.add("缺少窗口字段："+task.windowColumn());
    }
    private static boolean text(int type) { return Set.of(Types.CHAR,Types.VARCHAR,Types.NCHAR,Types.NVARCHAR).contains(type); }
    private static boolean decimal(int type) { return type==Types.DECIMAL||type==Types.NUMERIC; }
    private static boolean numeric(int type) {return Set.of(Types.DECIMAL,Types.NUMERIC,Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.FLOAT,Types.REAL,Types.DOUBLE).contains(type);}
    private static boolean temporal(int type) {return Set.of(Types.DATE,Types.TIME,Types.TIMESTAMP,Types.TIME_WITH_TIMEZONE,Types.TIMESTAMP_WITH_TIMEZONE).contains(type);}
}
