package io.dataease.company.bi.dataupdate;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import java.util.*;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;

/** Parses a deliberately small, single-object MySQL initialization language. */
public final class UpdateDdl {
    private UpdateDdl() { }
    public static List<String> validate(String sql, String schema, String table) {
        UpdateDefinition.identifier(table);
        require(sql != null && sql.length() <= 200_000, "建表 SQL 不能为空或超过 20 万字符");
        List<String> statements = UpdateSql.statements(sql);
        require(statements.size() <= 32, "一次初始化最多包含 32 条语句");
        for (int i=0; i<statements.size(); i++) {
            net.sf.jsqlparser.statement.Statement parsed;
            try { parsed = CCJSqlParserUtil.parse(statements.get(i)); }
            catch (Exception e) { throw new UpdateDefinition.ValidationException("第 " + (i+1) + " 条建表语句无法解析，请使用标准 CREATE TABLE / CREATE INDEX"); }
            if (i == 0 && parsed instanceof CreateTable create) {
                target(create.getTable(), schema, table);
                require(create.getSelect() == null && create.getLikeTable() == null && !create.isOrReplace(), "请显式声明字段，不支持 CTAS、LIKE 或替换表");
                require(create.getCreateOptionsStrings() == null || create.getCreateOptionsStrings().isEmpty(), "只支持普通实表，不支持临时表或其他建表修饰符");
                require(create.getColumnDefinitions() != null && !create.getColumnDefinitions().isEmpty(), "请声明目标字段");
                String words = UpdateSql.outsideLiterals(statements.get(i)).toUpperCase(Locale.ROOT);
                require(!java.util.regex.Pattern.compile("\\b(REFERENCES|FOREIGN|DIRECTORY|CONNECTION|TABLESPACE|PARTITION|UNION|SELECT|AS)\\b").matcher(words).find(), "建表入口只支持当前实表的字段、主键和索引；请移除外部引用或生成表达式");
                String options = create.getTableOptionsStrings() == null ? "" : String.join(" ", create.getTableOptionsStrings());
                String rest = options.replaceAll("(?is)COMMENT\\s*=?\\s*'(?:''|[^'])*'", "")
                        .replaceAll("(?i)ENGINE\\s*=?\\s*InnoDB\\b", "")
                        .replaceAll("(?i)(?:DEFAULT\\s+)?(?:CHARSET|CHARACTER\\s+SET|COLLATE)\\s*=?\\s*[A-Za-z0-9_]+", "").strip();
                require(rest.isEmpty(), "表选项仅支持 InnoDB、字符集、排序规则和备注");
            } else if (i > 0 && parsed instanceof CreateIndex index) {
                target(index.getTable(), schema, table);
                require(index.getTailParameters() == null || index.getTailParameters().isEmpty(), "请将索引写为普通 CREATE INDEX / CREATE UNIQUE INDEX");
            } else throw new UpdateDefinition.ValidationException("第一条必须是 CREATE TABLE，后续只能为该表 CREATE INDEX");
        }
        return statements;
    }
    private static void target(Table actual, String schema, String table) {
        require(actual != null && actual.getNameParts().size() <= 2, "对象必须属于当前数据库");
        require(unquote(actual.getName()).equals(table), "SQL 中的表名与目标表不一致");
        require(actual.getSchemaName() == null || unquote(actual.getSchemaName()).equals(schema), "SQL 中的数据库与当前目标数据库不一致");
    }
    private static String unquote(String value) {
        if (value != null && value.length() >= 2 && value.charAt(0)=='`' && value.charAt(value.length()-1)=='`') return value.substring(1,value.length()-1).replace("``","`");
        return value;
    }
}
