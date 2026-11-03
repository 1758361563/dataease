package io.dataease.company.bi.dataupdate;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UpdateRangeTest {
    private UpdateDefinition task(){return UpdateCoreTest.task("range",List.of());}
    private UpdateRange.Request request(String start,String end,UpdateDefinition.Strategy strategy){
        return new UpdateRange.Request(1,start,end,"order_date",strategy,false);
    }
    @Test void inclusiveRangeBecomesHalfOpenAndDoesNotMutateSavedTask(){
        var saved=task();var result=UpdateRange.prepare(saved,request("2024-02-28","2024-02-29",UpdateDefinition.Strategy.WINDOW),UpdateConnections.Dialect.SQLSERVER);
        assertEquals(LocalDate.of(2024,3,1),result.endExclusive());assertEquals(2,result.task().windowDays());
        assertEquals(UpdateDefinition.Strategy.FULL,saved.strategy());assertEquals(7,saved.windowDays());
        String sql=UpdateSql.sourceQuery(result.task(),result.endExclusive(),"target",UpdateConnections.Dialect.SQLSERVER);
        assertTrue(sql.contains("[order_date]>='2024-02-28 00:00:00'"));assertTrue(sql.contains("[order_date]<'2024-03-01 00:00:00'"));
        assertEquals(result.task(),UpdateStore.definition(UpdateStore.json(result.task())));
    }
    @Test void refusesReversedDatesAndDestructiveRangeFullReplacement(){
        assertThrows(IllegalArgumentException.class,()->UpdateRange.prepare(task(),request("2026-03-02","2026-03-01",UpdateDefinition.Strategy.WINDOW),UpdateConnections.Dialect.MYSQL));
        assertThrows(IllegalArgumentException.class,()->UpdateRange.prepare(task(),request("2026-03-01","2026-03-02",UpdateDefinition.Strategy.FULL),UpdateConnections.Dialect.MYSQL));
    }
    @Test void upsertRequiresKeys(){
        assertThrows(IllegalArgumentException.class,()->UpdateRange.prepare(task(),request("2026-03-01","2026-03-02",UpdateDefinition.Strategy.UPSERT),UpdateConnections.Dialect.MYSQL));
    }
    @Test void initialAllUsesUnfilteredQuery(){
        var result=UpdateRange.prepare(task(),new UpdateRange.Request(1,"","","",UpdateDefinition.Strategy.WINDOW,true),UpdateConnections.Dialect.MYSQL);
        assertEquals(task().sql(),result.task().sql());assertEquals(UpdateDefinition.Strategy.FULL,result.task().strategy());
    }
    @Test void dailyFullDoesNotAcquireImplicitDateFilter(){
        assertEquals(task().sql(),UpdateSql.sourceQuery(task(),LocalDate.of(2026,11,3),"target",UpdateConnections.Dialect.MYSQL));
    }
}
