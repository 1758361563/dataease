package io.dataease.company.bi.etl;

import io.dataease.extensions.datasource.dto.ConnectionObj;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static io.dataease.company.bi.etl.EtlModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Only runs against the dedicated local fixture port. Never reads application datasource configuration. */
@EnabledIfSystemProperty(named="etl.mysql.url", matches=".+")
class EtlMysqlTest {
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-28T04:00:00Z"),ZoneOffset.UTC);
    private Connection db;
    private String url;
    private EtlConnections.Handle handle() throws Exception {
        ConnectionObj object=new ConnectionObj();object.setConnection(connect());return new EtlConnections.Handle(object);
    }
    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url,System.getProperty("etl.mysql.user","root"),System.getenv("ETL_TEST_MYSQL_PASSWORD"));
    }
    @BeforeEach void setup() throws Exception {
        url=System.getProperty("etl.mysql.url");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:33328/qiwei_new_bi"),"Integration tests require the isolated localhost fixture");
        db=connect();
        try(Statement s=db.createStatement()) {
            s.execute("DROP TABLE IF EXISTS etl_run_log");
            s.execute("CREATE TABLE etl_run_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,etl_name VARCHAR(128) NOT NULL,kind VARCHAR(16) NOT NULL,status VARCHAR(16),started_at DATETIME,finished_at DATETIME,window_start DATE,window_end DATE,planned_at DATETIME NOT NULL,trigger_type VARCHAR(16),row_count BIGINT,note VARCHAR(512),UNIQUE KEY uk_plan(etl_name,kind,planned_at)) ENGINE=InnoDB");
            for(int i=0;i<3;i++) {
                s.execute("DROP TABLE IF EXISTS fixture_"+i);
                s.execute("CREATE TABLE fixture_"+i+"(orderDate DATE,actualMoney DECIMAL(18,4)) ENGINE=InnoDB");
                s.execute("INSERT INTO fixture_"+i+" VALUES('2026-01-01',10),('2026-10-27',5)");
            }
        }
        EtlSqlScript.prepare(db);
    }
    @AfterEach void close() throws Exception { if(db!=null) db.close(); }
    EtlStore.Batch batch(String planned,String trigger) {
        return new EtlStore.Batch(planned,"refresh",1,1,"2026-09-23","2026-10-28",EtlPackageValidator.NAMES,trigger,false);
    }
    SqlPackage fixture(boolean breakFirst,boolean badBaseline) {
        List<Task> tasks=new ArrayList<>();Map<String,String> files=new LinkedHashMap<>();
        for(int i=0;i<3;i++) {
            String path="task"+i+".sql";
            String sql="START TRANSACTION;DELETE FROM fixture_"+i+" WHERE orderDate>='__REQUIRED_WINDOW_START__';"
                    +(breakFirst && i==0 ? "INSERT INTO absent_table VALUES(1);" : "INSERT INTO fixture_"+i+" VALUES('2026-10-27',7);")+"COMMIT;";
            files.put(path,sql);
            tasks.add(new Task(EtlPackageValidator.NAMES.get(i),"qiwei_new_bi","fixture_"+i,"orderDate",i==0 ? List.of() : List.of(EtlPackageValidator.NAMES.get(i-1)),path,"fixture",
                    new Acceptance("actualMoney","2026-01-01",new Baseline("2026-07",badBaseline ? 1 : 0,BigDecimal.ZERO,new BigDecimal("0.01")))));
        }
        return new SqlPackage(1,new Manifest(1,"formal","refresh",new Source("etl_migration","abcdef1","2026-10-28 12:00:00"),35,tasks,List.of()),files);
    }
    EtlChainRunner runner(SqlPackage pack) throws Exception {
        EtlStore store=mock(EtlStore.class);when(store.pin(any())).thenAnswer(call->call.getArgument(0));when(store.load(anyLong())).thenReturn(pack);
        EtlConnections connections=mock(EtlConnections.class);when(connections.open(anyLong())).thenAnswer(call->handle());
        return new EtlChainRunner(store,connections,mock(EtlSwitchService.class),CLOCK);
    }
    long count(String sql) throws Exception {
        db.commit();try(Statement s=db.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getLong(1);}
    }
    @Test void gtidAndMysqlVersionMatchProduction() throws Exception {
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT VERSION(),@@gtid_mode,@@enforce_gtid_consistency")) {
            r.next();assertTrue(r.getString(1).startsWith("5.7.44"));assertEquals("ON",r.getString(2));assertEquals("ON",r.getString(3));
        }
        assertThrows(SQLException.class,()->{try(Statement s=db.createStatement()){s.execute("CREATE TABLE fixture_gtid_bad AS SELECT * FROM fixture_0");}});
    }
    @Test void failureRollsBackWindowAndBlocksDownstream() throws Exception {
        var runner=runner(fixture(true,false));
        try { runner.run(batch("2026-10-28 03:00:00","schedule"),false); }
        finally { runner.stop(); }
        assertEquals(15,count("SELECT SUM(actualMoney) FROM fixture_0"));
        assertEquals(2,count("SELECT COUNT(*) FROM fixture_0"));
        assertEquals(1,count("SELECT COUNT(*) FROM etl_run_log WHERE status='fail'"));
        assertEquals(2,count("SELECT COUNT(*) FROM etl_run_log WHERE status='blocked'"));
        assertEquals(5,count("SELECT actualMoney FROM fixture_1 WHERE orderDate='2026-10-27'"));
    }
    @Test void successRunsOnlyOnceAndPreservesHistory() throws Exception {
        var runner=runner(fixture(false,false));var batch=batch("2026-10-28 03:00:00","schedule");
        try { runner.run(batch,false);runner.run(batch,false); }
        finally { runner.stop(); }
        assertEquals(3,count("SELECT COUNT(*) FROM etl_run_log WHERE status='success'"));
        assertEquals(3,count("SELECT COUNT(*) FROM etl_run_log"));
        assertEquals(10,count("SELECT actualMoney FROM fixture_0 WHERE orderDate='2026-01-01'"));
        assertEquals(7,count("SELECT actualMoney FROM fixture_2 WHERE orderDate='2026-10-27'"));
    }
    @Test void existingRunningIsOnlyExecutedByRecovery() throws Exception {
        var batch=batch("2026-10-28 03:00:00","schedule");
        new EtlRunLogRepository(db,CLOCK).claim(EtlPackageValidator.NAMES.getFirst(),batch);
        var runner=runner(fixture(false,false));
        try {
            runner.run(batch,false);assertEquals(5,count("SELECT actualMoney FROM fixture_0 WHERE orderDate='2026-10-27'"));
            // A real process crash leaves only the claimed task, not downstream terminal rows.
            try(Statement s=db.createStatement()){s.execute("DELETE FROM etl_run_log WHERE status='blocked'");db.commit();}
            runner.run(batch,true);
        } finally {runner.stop();}
        assertEquals(3,count("SELECT COUNT(*) FROM etl_run_log WHERE status='success'"));
    }
    @Test void committedAcceptanceFailureIsRecordedAndBlocksDownstream() throws Exception {
        var runner=runner(fixture(false,true));
        try {runner.run(batch("2026-10-28 03:00:00","schedule"),false);}finally{runner.stop();}
        assertEquals(7,count("SELECT actualMoney FROM fixture_0 WHERE orderDate='2026-10-27'"));
        assertEquals(1,count("SELECT COUNT(*) FROM etl_run_log WHERE status='fail' AND note LIKE '提交后验收失败%'"));
        assertEquals(2,count("SELECT COUNT(*) FROM etl_run_log WHERE status='blocked'"));
    }
    @Test void uniqueClaimWithConcurrentConnections() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        Callable<Boolean> claim=()->{try(var h=handle()){EtlSqlScript.prepare(h.connection());start.await();return new EtlRunLogRepository(h.connection(),CLOCK).claim("销售订单数据-线下",batch("2026-10-28 03:00:00","schedule")).created();}};
        try {var a=pool.submit(claim);var b=pool.submit(claim);start.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));}
        finally {pool.shutdownNow();}
        assertEquals(1,count("SELECT COUNT(*) FROM etl_run_log"));
    }
    @Test void lockSerializesInstancesAndDetectsKilledConnection() throws Exception {
        var owner=handle();long connectionId;
        try(Statement s=owner.connection().createStatement();ResultSet r=s.executeQuery("SELECT CONNECTION_ID()")){r.next();connectionId=r.getLong(1);}
        EtlLock first=new EtlLock(owner);
        try {
            assertTrue(first.acquired());
            try(EtlLock second=new EtlLock(handle())){assertFalse(second.acquired());}
            try(Statement s=db.createStatement()){s.execute("KILL "+connectionId);}
            assertThrows(IllegalStateException.class,first::check);
            try(EtlLock third=new EtlLock(handle())){assertTrue(third.acquired());}
        } finally {first.close();}
    }
    @Test void recoveryBoundaryAndManualInterruption() throws Exception {
        var logs=new EtlRunLogRepository(db,CLOCK);
        logs.claim("销售订单数据-线下",batch("2026-10-27 00:00:00","schedule"));
        logs.claim("销售订单数据-线下",batch("2026-10-26 23:59:59","schedule"));
        logs.claim("销售订单数据-线下",batch("2026-10-28 11:00:00","manual"));
        try(Statement s=db.createStatement()) {
            s.execute("UPDATE etl_run_log SET started_at='2026-10-27 00:00:00' WHERE planned_at='2026-10-27 00:00:00'");
            s.execute("UPDATE etl_run_log SET started_at='2026-10-26 23:59:59' WHERE planned_at='2026-10-26 23:59:59'");db.commit();
        }
        assertEquals(List.of("2026-10-27 00:00:00"),logs.resumable());
        logs.interruptManual();assertEquals(1,count("SELECT COUNT(*) FROM etl_run_log WHERE trigger_type='manual' AND status='fail'"));
    }
    @Test void manualDependencyUsesLatestSuccessInSameKindAndWindow() throws Exception {
        var logs=new EtlRunLogRepository(db,CLOCK);
        var first=logs.claim("销售订单数据-线下",batch("2026-10-28 03:00:00","schedule")).row();
        logs.finish(first.id(),"success",2L,"ok");
        assertEquals(first.id(),logs.manualUpstream("销售订单数据-线下","refresh","2026-10-28"));
        assertNull(logs.manualUpstream("销售订单数据-线下","refresh_test","2026-10-28"));
        assertNull(logs.manualUpstream("销售订单数据-线下","refresh","2026-10-27"));
        var latest=logs.claim("销售订单数据-线下",batch("2026-10-28 10:00:00","manual")).row();
        logs.finish(latest.id(),"fail",null,"fixture failure");
        assertNull(logs.manualUpstream("销售订单数据-线下","refresh","2026-10-28"));
    }
    @Test void metadataPinsVersionAndRetainsRejectedArchive() throws Exception {
        var source=new DriverManagerDataSource(url,System.getProperty("etl.mysql.user","root"),System.getenv("ETL_TEST_MYSQL_PASSWORD"));
        EtlStore store=new EtlStore(source);
        try(Statement s=db.createStatement()) {
            for(String name:List.of("setting","task","package","package_file","batch")) {
                s.execute("DROP TABLE IF EXISTS company_etl_"+name);
                s.execute(Files.readString(Path.of(System.getProperty("etl.schema"),"company_etl_"+name+".sql")));
            }
        }
        store.initialize();assertFalse(store.setting().schedulerEnabled());
        Map<String,String> files=EtlCoreTest.files(false);var manifest=EtlPackageValidator.validate(files);
        long first=store.savePackage(new byte[]{1},files,manifest,null,1);store.activate(first);
        var batch=batch("2026-10-28 03:00:00","schedule");assertEquals(first,store.pin(batch).packageId());
        long second=store.savePackage(new byte[]{2},files,manifest,null,1);store.activate(second);
        assertEquals(second,store.setting().activePackageId());assertEquals(first,store.batch(batch.plannedAt(),batch.kind()).packageId());
        long rejected=store.savePackage(new byte[]{3},Map.of(),null,"无效 ZIP",1);assertThrows(IllegalArgumentException.class,()->store.activate(rejected));
    }
}
