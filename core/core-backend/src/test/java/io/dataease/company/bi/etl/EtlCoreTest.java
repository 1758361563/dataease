package io.dataease.company.bi.etl;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import static io.dataease.company.bi.etl.EtlModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtlCoreTest {
    static Task task(int index, boolean test) {
        return new Task(EtlPackageValidator.NAMES.get(index),"qiwei_new_bi",EtlPackageValidator.TABLES.get(index)+(test ? "_dataease_test" : ""),
                index==2 ? "支付时间(天)" : "orderDate",index==0 ? List.of() : List.of(EtlPackageValidator.NAMES.get(index-1)),
                "task"+index+".sql",EtlPackageValidator.hash(sql(index,test).getBytes(StandardCharsets.UTF_8)),
                new Acceptance("actualMoney","2026-01-01",new Baseline("2026-07",new long[]{20826,132194,24272}[index],
                        new BigDecimal(index==0 ? "1901999.6700" : "5973923.4500"),new BigDecimal("0.01"))));
    }
    static String sql(int i, boolean test) {
        return "SET @a='__REQUIRED_WINDOW_START__';SET @b='__REQUIRED_WINDOW_END__';SET @c='__SAFETY_SMOKE_PASSED__';"
                + "START TRANSACTION;DELETE FROM `qiwei_new_bi`.`"+EtlPackageValidator.TABLES.get(i)+(test ? "_dataease_test" : "")+"` WHERE 1=0;COMMIT;";
    }
    static Map<String,String> files(boolean test) {
        Map<String,String> files=new LinkedHashMap<>();List<Task> tasks=new ArrayList<>();
        for(int i=0;i<3;i++) { Task task=task(i,test);tasks.add(task);files.put(task.sql(),sql(i,test)); }
        files.put("manifest.json",json(new Manifest(1,test ? "test" : "formal",test ? "refresh_test" : "refresh",new Source("etl_migration","abcdef1","2026-10-28 10:00:00"),35,tasks,List.of())));
        return files;
    }
    @Test void parserMatchesPythonLineAndSemicolonRules() {
        assertEquals(List.of("SET @a=1","SELECT 'a","b'","SELECT 2"),EtlSqlScript.statements(" -- comment\nSET @a=1; SELECT 'a;b';;\n--gone\nSELECT 2;"));
    }
    @Test void placeholdersAndGuardsAreValidatedBeforeExecution() {
        String rendered=EtlSqlScript.render(sql(0,false),new Window("2026-09-23","2026-10-28"));
        assertTrue(rendered.contains("2026-09-23"));assertTrue(rendered.contains("YES"));
        for(String invalid:List.of("SELECT '__REQUIRED_UNKNOWN__'","RENAME\nTABLE a TO b","SELECT 'RENAME TABLE'","START TRANSACTION;CREATE TABLE a(id int);COMMIT","START TRANSACTION;DELETE FROM a"))
            assertThrows(IllegalArgumentException.class,()->EtlSqlScript.render(invalid,new Window("2026-09-23","2026-10-28")));
    }
    @Test void timezoneAndWindowAreIndependentOfJvm() {
        TimeZone original=TimeZone.getDefault();
        try { TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertEquals(new Window("2025-11-27","2026-01-01"),Window.at(Instant.parse("2025-12-31T16:00:00Z")));
            assertEquals(new Window("2024-01-26","2024-03-01"),Window.at(Instant.parse("2024-02-29T16:00:00Z")));
        } finally { TimeZone.setDefault(original); }
    }
    @Test void testWindowHasExactBoundaries() {
        assertTrue(EtlSwitchService.testTimeAllowed(Instant.parse("2026-10-27T18:29:59Z")));
        assertFalse(EtlSwitchService.testTimeAllowed(Instant.parse("2026-10-27T18:30:00Z")));
        assertFalse(EtlSwitchService.testTimeAllowed(Instant.parse("2026-10-27T20:59:59Z")));
        assertTrue(EtlSwitchService.testTimeAllowed(Instant.parse("2026-10-27T21:00:00Z")));
    }
    @Test void acceptanceToleranceAndIndependentFailures() {
        Task task=task(0,false);Window window=new Window("2026-09-23","2026-10-28");
        var base=new EtlAcceptance.Metrics(30000,20000,new BigDecimal("123"),20826,new BigDecimal("1901999.6800"),"2026-01-01","2026-10-27",100);
        assertTrue(EtlAcceptance.issues(base,base,task,window).isEmpty());
        var tooMuch=new EtlAcceptance.Metrics(30000,20000,new BigDecimal("123"),20826,new BigDecimal("1901999.6810"),"2026-01-01","2026-10-27",100);
        assertTrue(EtlAcceptance.issues(tooMuch,tooMuch,task,window).contains("与对账基线不一致"));
        for(var bad:List.of(
                new EtlAcceptance.Metrics(30000,19999,base.historyMoney(),20826,base.baselineMoney(),base.min(),base.max(),100),
                new EtlAcceptance.Metrics(30000,20000,BigDecimal.ZERO,20826,base.baselineMoney(),base.min(),base.max(),100),
                new EtlAcceptance.Metrics(30000,20000,base.historyMoney(),20825,base.baselineMoney(),base.min(),base.max(),100),
                new EtlAcceptance.Metrics(30000,20000,base.historyMoney(),20826,base.baselineMoney(),base.min(),base.max(),0),
                new EtlAcceptance.Metrics(30000,20000,base.historyMoney(),20826,base.baselineMoney(),"2026-01-02",base.max(),100),
                new EtlAcceptance.Metrics(30000,20000,base.historyMoney(),20826,base.baselineMoney(),base.min(),window.end(),100)))
            assertFalse(EtlAcceptance.issues(base,bad,task,window).isEmpty());
    }
    @Test void validatesFormalAndTestPackages() { assertEquals("refresh",EtlPackageValidator.validate(files(false)).runKind());assertEquals("refresh_test",EtlPackageValidator.validate(files(true)).runKind()); }
    @Test void rejectsHashesCyclesMissingPlaceholdersAndTestWrites() {
        var hashes=files(false);hashes.put("task0.sql","SELECT 1");assertThrows(IllegalArgumentException.class,()->EtlPackageValidator.validate(hashes));
        var cycle=files(false);cycle.put("manifest.json",cycle.get("manifest.json").replace("\"depends_on\":[]","\"depends_on\":[\"产品分渠道销售\"]"));
        assertThrows(IllegalArgumentException.class,()->EtlPackageValidator.validate(cycle));
        assertThrows(IllegalArgumentException.class,()->EtlPackageValidator.validateTestSql("DELETE FROM qiwei_new_bi.销售订单数据_线下",false));
        assertThrows(IllegalArgumentException.class,()->EtlPackageValidator.validateTestSql("DELETE FROM `qiwei_new_bi`.`销售订单数据_线下_refresh_stage`",false));
        assertThrows(IllegalArgumentException.class,()->EtlPackageValidator.validateTestSql("CALL dangerous()",true));
        assertDoesNotThrow(()->EtlPackageValidator.validateTestSql("CREATE TABLE `qiwei_new_bi`.`销售订单数据_线下_dataease_test` LIKE `qiwei_new_bi`.`销售订单数据_线下`;INSERT INTO `销售订单数据_线下_dataease_test` SELECT * FROM `销售订单数据_线下`",true));
    }
    @Test void zipRejectsTraversalAndUnexpectedFiles() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry("../manifest.json"));zip.write("{}".getBytes(StandardCharsets.UTF_8));zip.closeEntry(); }
        assertThrows(IllegalArgumentException.class,()->EtlPackageValidator.unpack(bytes.toByteArray()));
        var extra=files(false);extra.put("extra.sql","SELECT 1");assertThrows(IllegalArgumentException.class,()->EtlPackageValidator.validate(extra));
    }
    @Test void transactionCommitAndStopSemantics() throws Exception {
        Connection connection=mock(Connection.class);Statement statement=mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);when(statement.getUpdateCount()).thenReturn(-1);
        EtlSqlScript.execute(connection,"SET @a=1;SELECT '__STOP__';START TRANSACTION;DELETE FROM a;INSERT INTO a VALUES(1);COMMIT;DROP TABLE stage;",()->{},s->{});
        verify(connection,times(2)).commit();verify(statement,never()).execute("SELECT '__STOP__'");
        var order=inOrder(statement);order.verify(statement).execute("START TRANSACTION");order.verify(statement).execute("DELETE FROM a");order.verify(statement).execute("INSERT INTO a VALUES(1)");order.verify(statement).execute("COMMIT");
    }
    @Test void failureRollsBackAndNeverCommitsInsideTransaction() throws Exception {
        Connection connection=mock(Connection.class);Statement statement=mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);when(statement.getUpdateCount()).thenReturn(-1);when(statement.execute("BAD")).thenThrow(new SQLException("secret-row-data","42000"));
        assertThrows(SQLException.class,()->EtlSqlScript.execute(connection,"START TRANSACTION;DELETE FROM a;BAD;COMMIT;",()->{},s->{}));
        verify(connection,never()).commit();verify(statement).execute("ROLLBACK");verify(connection).rollback();
    }
    @Test void safeDatabaseErrorNeverContainsCredentialsOrSql() {
        assertEquals("数据库操作失败，SQLState=42000，错误码=1045",error(new SQLException("jdbc:mysql://host password=secret SQL-row","42000",1045)));
    }
    @Test void cronNeverCatchesUpMissedSlots() throws Exception {
        var trigger=EtlQuartzRegistrar.trigger("03:00",Instant.parse("2026-10-28T00:00:00Z"));
        assertEquals(org.quartz.CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING,trigger.getMisfireInstruction());
        assertEquals(TimeZone.getTimeZone(ZONE),trigger.getTimeZone());
        assertEquals(Instant.parse("2026-10-28T19:00:00Z"),trigger.getFireTimeAfter(java.util.Date.from(Instant.parse("2026-10-28T00:00:00Z"))).toInstant());
    }
    @Test void realSqlMatchesPythonSnapshots() throws Exception {
        Path fixtures=Path.of(System.getProperty("etl.fixtures","src/test/resources/company-etl"));
        for(int i=0;i<3;i++) {
            String sql=Files.readString(fixtures.resolve("refresh-"+i+".sql"));
            List<String> expected=Files.readAllLines(fixtures.resolve("refresh-"+i+".sha256"));
            assertEquals(expected,EtlSqlScript.statements(sql).stream().map(s->EtlPackageValidator.hash(s.getBytes(StandardCharsets.UTF_8))).toList());
            assertDoesNotThrow(()->EtlSqlScript.validate(sql));
        }
    }
}
