package io.dataease.company.bi.dataupdate;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;

/** Creates a one-run snapshot without changing the saved daily configuration. */
public final class UpdateRange {
    public record Request(long version, String start, String end, String column,
                          UpdateDefinition.Strategy strategy, boolean all) { }
    public record Execution(UpdateDefinition task, LocalDate endExclusive) { }
    public static Execution prepare(UpdateDefinition task, Request request, UpdateConnections.Dialect source) {
        require(task.mode()!=UpdateDefinition.Mode.FEISHU,"飞书快照不支持日期补跑");
        if(request.all()) {
            require(task.mode()==UpdateDefinition.Mode.QUERY,"全部数据初始化适用于查询抽取");
            require(!task.sql().contains("{{window_"),"查询含日期参数，请选择指定范围；读取全部数据需提供不含日期参数的查询");
            var full=new UpdateDefinition(task.id(),task.version(),task.datasourceId(),task.schema(),task.table(),task.mode(),
                    task.sourceDatasourceId(),task.sql(),UpdateDefinition.Strategy.FULL,task.keys(),"",1,task.allowEmpty(),
                    task.trigger(),task.enabled(),task.cron(),task.timezone(),task.dependencies(),task.timeoutSeconds(),task.maxRows(),
                    task.owner(),task.structureFingerprint(),task.minimumRows(),task.sheet());
            full.validate();return new Execution(full,task.businessDate(java.time.Instant.now()));
        }
        LocalDate start, end;
        try { start=LocalDate.parse(request.start()); end=LocalDate.parse(request.end()).plusDays(1); }
        catch(Exception e) { throw new UpdateDefinition.ValidationException("请选择有效的起止日期"); }
        long days=ChronoUnit.DAYS.between(start,end);
        require(start.getYear()>=1970 && end.getYear()<=9999 && days>=1 && days<=3660,"日期范围须为 1–3660 天");
        String sql=task.sql();
        boolean parameters=sql.contains("{{window_start}}") && sql.contains("{{window_end}}");
        var strategy=request.strategy();
        if(task.mode()==UpdateDefinition.Mode.QUERY) {
            require(strategy==UpdateDefinition.Strategy.WINDOW || strategy==UpdateDefinition.Strategy.UPSERT,"范围同步请选择区间覆盖或主键合并");
            if(strategy==UpdateDefinition.Strategy.WINDOW || !parameters)UpdateDefinition.identifier(request.column());
            if(!parameters) {
                require(!sql.contains("{{window_"),"范围查询须同时使用开始和结束参数");
                sql="SELECT * FROM ("+UpdateSql.requireQuery(sql)+") range_source WHERE "+source.quote(request.column())+
                        ">={{window_start}} AND "+source.quote(request.column())+"<{{window_end}}";
            }
        } else require(parameters,"脚本补跑须同时使用 {{window_start}} 和 {{window_end}} 参数");
        var snapshot=new UpdateDefinition(task.id(),task.version(),task.datasourceId(),task.schema(),task.table(),
                task.mode(),task.sourceDatasourceId(),sql,strategy,task.keys(),UpdateDefinition.clean(request.column()),(int)days,
                task.allowEmpty(),task.trigger(),task.enabled(),task.cron(),task.timezone(),task.dependencies(),
                task.timeoutSeconds(),task.maxRows(),task.owner(),task.structureFingerprint(),task.minimumRows(),task.sheet());
        snapshot.validate();return new Execution(snapshot,end);
    }
}
