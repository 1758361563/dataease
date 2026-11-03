package io.dataease.company.bi.dataupdate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.util.*;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;

@Component
@ConditionalOnProperty(name="company.bi.auth.enabled",havingValue="true",matchIfMissing=true)
public class SheetExecutor {
    private final SheetReader reader;
    public SheetExecutor(SheetReader reader) { this.reader=reader; }
    public long execute(UpdateDefinition task,Connection target,UpdateConnections.Dialect dialect,String table,UpdateExecution execution) throws Exception {
        require(dialect==UpdateConnections.Dialect.MYSQL,"飞书同步首期支持 MySQL 目标");
        var shape=UpdateInspection.structure(target,task.schema(),task.table());
        require(shape.fingerprint().equals(task.structureFingerprint()),"目标结构已变化，请重新预览");
        try(var ps=target.prepareStatement("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE EVENT_OBJECT_SCHEMA=? AND EVENT_OBJECT_TABLE=?")) {
            ps.setString(1,task.schema());ps.setString(2,task.table());
            try(var rs=ps.executeQuery()){rs.next();require(rs.getInt(1)==0,"飞书快照目标不能含触发器，请使用独立落库表");}
        }
        try(var ps=target.prepareStatement("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE REFERENCED_TABLE_SCHEMA=? AND REFERENCED_TABLE_NAME=?")) {
            ps.setString(1,task.schema());ps.setString(2,task.table());
            try(var rs=ps.executeQuery()){rs.next();require(rs.getInt(1)==0,"飞书快照目标不能被外键引用，请使用独立落库表");}
        }
        try(var ps=target.prepareStatement("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            ps.setString(1,task.schema().isEmpty()?target.getCatalog():task.schema()); ps.setString(2,task.table());
            try(var rs=ps.executeQuery()) { require(rs.next() && "InnoDB".equalsIgnoreCase(rs.getString(1)),"同步目标必须为 InnoDB 实表"); }
        }
        // A transaction publishes the complete snapshot while preserving indexes, identity and table grants.
        command(target,"SET SESSION sql_mode = CONCAT_WS(',', @@sql_mode, 'STRICT_ALL_TABLES')",execution);
        SheetSource source=task.sheet(); List<String> headers=reader.headers(source);
        SheetValues.mapping(source,headers,shape);
        String stage=dialect.quote("fs_"+UUID.randomUUID().toString().replace("-",""));
        command(target,"CREATE TEMPORARY TABLE "+stage+" LIKE "+table,execution);
        List<String> names=source.fields().stream().map(SheetSource.Field::target).toList();
        String columns=String.join(",",names.stream().map(dialect::quote).toList());
        var statement=execution.watch(target.prepareStatement("INSERT INTO "+stage+" ("+columns+") VALUES ("+String.join(",",Collections.nCopies(names.size(),"?"))+")"));
        long count=0,scanned=0;
        int expectedRows=-1;
        try(statement) {
            int start=source.dataStartRow(), limit=source.dataEndRow()==null?1_000_000:source.dataEndRow();
            while(start<=limit) {
                execution.check(); require((long)start-source.dataStartRow()<task.maxRows(),"读取范围超过行数上限，请缩小范围或提高上限");
                int end=(int)Math.min(Math.min((long)start+99,limit),(long)source.dataStartRow()+task.maxRows()-1);
                SheetReader.Page page=reader.read(source,start,end); execution.check(); limit=Math.min(limit,page.sheetRows());
                if(expectedRows<0)expectedRows=page.sheetRows();
                require(expectedRows==page.sheetRows(),"读取过程中工作表行数变化，请重新同步");
                scanned+=Math.max(0,Math.min(end,page.sheetRows())-start+1);
                for(int r=0;r<page.rows().size();r++) {
                    var row=page.rows().get(r);
                    if(row.stream().allMatch(v->v==null || v.toString().isEmpty())) continue;
                    for(int i=0;i<source.fields().size();i++) {
                        var field=source.fields().get(i); int index=headers.indexOf(field.source());
                        var column=shape.columns().stream().filter(c->c.name().equals(field.target())).findFirst().orElseThrow();
                        statement.setObject(i+1,SheetValues.convert(index<row.size()?row.get(index):null,field,column,source.nullValues(),start+r));
                    }
                    statement.addBatch(); count++;
                }
                statement.executeBatch(); statement.clearBatch(); start=end+1;
            }
        } finally { execution.forget(statement); }
        require(count>0 || task.allowEmpty(),"读取结果为空，保留原快照");
        require(count>=task.minimumRows(),"读取行数少于最小行数，保留原快照");
        require(headers.equals(reader.headers(source)),"读取过程中表头发生变化，请重新同步");
        execution.beforeCommit();
        command(target,"DELETE FROM "+table,execution);
        command(target,"INSERT INTO "+table+" ("+columns+") SELECT "+columns+" FROM "+stage,execution);
        execution.resultMessage("扫描 "+scanned+" 行，读取有效数据 "+count+" 行，写入 "+count+" 行，映射 "+source.fields().size()+" 个字段");
        return count;
    }
    private void command(Connection c,String sql,UpdateExecution execution)throws Exception {
        Statement s=execution.watch(c.createStatement()); try(s){s.execute(sql);}finally{execution.forget(s);}
    }
}
