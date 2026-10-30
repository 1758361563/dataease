package io.dataease.company.bi.dataupdate;

import io.dataease.company.bi.etl.EtlConnections;
import io.dataease.extensions.datasource.dto.ConnectionObj;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** JDBC doubles exercise commit/rollback paths without a database or data access. */
class UpdateExecutorTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 29);
    static class Fixture {
        final UpdateConnections connections = mock(UpdateConnections.class);
        final Connection target = mock(Connection.class), source = mock(Connection.class);
        final DatabaseMetaData targetMeta = mock(DatabaseMetaData.class), sourceMeta = mock(DatabaseMetaData.class);
        final ResultSet data = mock(ResultSet.class), targetFields = mock(ResultSet.class), empty = mock(ResultSet.class);
        final ResultSetMetaData fields = mock(ResultSetMetaData.class);
        final PreparedStatement insert = mock(PreparedStatement.class);
        final List<String> commands = new ArrayList<>();
        final UpdateExecutor executor = new UpdateExecutor(connections);
        Fixture(boolean hasRow) throws Exception {
            when(connections.schema("123456789012345678")).thenReturn("analytics");
            when(target.getMetaData()).thenReturn(targetMeta); when(source.getMetaData()).thenReturn(sourceMeta);
            when(targetMeta.getDatabaseProductName()).thenReturn("PostgreSQL"); when(sourceMeta.getDatabaseProductName()).thenReturn("PostgreSQL");
            when(targetMeta.supportsTransactions()).thenReturn(true);
            when(fields.getColumnCount()).thenReturn(2); when(fields.getColumnLabel(1)).thenReturn("id"); when(fields.getColumnLabel(2)).thenReturn("amount");
            when(targetFields.getMetaData()).thenReturn(fields); when(data.getMetaData()).thenReturn(fields);
            when(data.next()).thenReturn(hasRow, false); when(data.getObject(1)).thenReturn(7); when(data.getObject(2)).thenReturn(new java.math.BigDecimal("12.30"));
            when(target.createStatement()).thenAnswer(invocation -> {
                Statement statement=mock(Statement.class);
                when(statement.executeUpdate(anyString())).thenAnswer(call -> { commands.add(call.getArgument(0)); return 1; });
                when(statement.execute(anyString())).thenAnswer(call -> { commands.add(call.getArgument(0)); return false; });
                when(statement.getUpdateCount()).thenReturn(-1);
                when(statement.executeQuery(anyString())).thenAnswer(call -> ((String)call.getArgument(0)).contains("WHERE 1=0") ? targetFields : empty);
                return statement;
            });
            Statement query=mock(Statement.class); when(source.createStatement(anyInt(),anyInt())).thenReturn(query); when(query.executeQuery(anyString())).thenReturn(data);
            when(target.prepareStatement(startsWith("INSERT INTO"))).thenReturn(insert);
            when(connections.open("123456789012345678")).thenReturn(handle(target)); when(connections.open("2")).thenReturn(handle(source));
        }
        static EtlConnections.Handle handle(Connection connection) { ConnectionObj object=new ConnectionObj(); object.setConnection(connection); return new EtlConnections.Handle(object); }
        long run(UpdateDefinition task, UpdateExecution execution) throws Exception { return executor.execute(task,DATE,execution); }
    }
    @Test void fullExtractionStagesBeforeReplacingAndCommitsOnce() throws Exception {
        Fixture f=new Fixture(true);
        assertEquals(1,f.run(UpdateCoreTest.task("a",List.of()),new UpdateExecution(60)));
        verify(f.insert).executeBatch(); verify(f.target).commit(); verify(f.target,never()).rollback();
        int create=index(f.commands,"CREATE TEMPORARY"), delete=index(f.commands,"DELETE FROM"), insert=index(f.commands,"INSERT INTO");
        assertTrue(create<delete && delete<insert); verify(f.source).setReadOnly(true); verify(f.source).rollback();
    }
    @Test void emptyExtractionDoesNotDeleteTarget() throws Exception {
        Fixture f=new Fixture(false);
        assertThrows(IllegalArgumentException.class,()->f.run(UpdateCoreTest.task("a",List.of()),new UpdateExecution(60)));
        assertEquals(-1,index(f.commands,"DELETE FROM")); verify(f.target).rollback(); verify(f.target,never()).commit();
    }
    @Test void insertFailureRollsBackTarget() throws Exception {
        Fixture f=new Fixture(true); when(f.insert.executeBatch()).thenThrow(new SQLException("simulated", "22001"));
        assertThrows(SQLException.class,()->f.run(UpdateCoreTest.task("a",List.of()),new UpdateExecution(60)));
        verify(f.target).rollback(); verify(f.target,never()).commit(); assertEquals(-1,index(f.commands,"DELETE FROM"));
    }
    @Test void lostOwnershipBeforeCommitRollsBackEvenAfterWrites() throws Exception {
        Fixture f=new Fixture(true); UpdateExecution execution=new UpdateExecution(60); execution.commitGuard(()->false);
        assertThrows(SQLException.class,()->f.run(UpdateCoreTest.task("a",List.of()),execution));
        verify(f.target).rollback(); verify(f.target,never()).commit(); assertTrue(index(f.commands,"DELETE FROM")>=0);
    }
    @Test void duplicateKeysPreventWritingTarget() throws Exception {
        Fixture f=new Fixture(true); when(f.empty.next()).thenReturn(false,true);
        var task=withStrategy(UpdateDefinition.Strategy.UPSERT,List.of("id"),false);
        assertThrows(IllegalArgumentException.class,()->f.run(task,new UpdateExecution(60)));
        verify(f.target).rollback(); assertEquals(-1,index(f.commands,"UPDATE"));
    }
    @Test void upsertUpdatesMappedFieldsWithoutDeletingRows() throws Exception {
        Fixture f=new Fixture(true);
        f.run(withStrategy(UpdateDefinition.Strategy.UPSERT,List.of("id"),false),new UpdateExecution(60));
        assertEquals(-1,index(f.commands,"DELETE"));
        assertTrue(f.commands.stream().anyMatch(sql -> sql.startsWith("UPDATE") && sql.contains("SET \"amount\"=s.\"amount\"")));
        assertTrue(f.commands.stream().anyMatch(sql -> sql.startsWith("INSERT INTO") && sql.contains("NOT EXISTS")));
    }
    @Test void emptyResultCanBeExplicitlyAllowed() throws Exception {
        Fixture f=new Fixture(false); assertEquals(0,f.run(withStrategy(UpdateDefinition.Strategy.FULL,List.of(),true),new UpdateExecution(60)));
        assertTrue(index(f.commands,"DELETE FROM")>=0); verify(f.target).commit();
    }
    @Test void outOfWindowRowsPreventDeletion() throws Exception {
        Fixture f=new Fixture(true); when(f.empty.next()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,()->f.run(withStrategy(UpdateDefinition.Strategy.WINDOW,List.of(),false),new UpdateExecution(60)));
        assertEquals(-1,index(f.commands,"DELETE FROM")); verify(f.target).rollback();
    }
    @Test void sqlServerIdentityInsertIsScopedToTargetWrite() throws Exception {
        Fixture f=new Fixture(true); when(f.targetMeta.getDatabaseProductName()).thenReturn("Microsoft SQL Server"); when(f.fields.isAutoIncrement(1)).thenReturn(true);
        f.run(UpdateCoreTest.task("a",List.of()),new UpdateExecution(60));
        assertTrue(f.commands.stream().anyMatch(sql -> sql.startsWith("SET IDENTITY_INSERT") && sql.endsWith(" ON")));
        assertTrue(f.commands.stream().anyMatch(sql -> sql.startsWith("SET IDENTITY_INSERT") && sql.endsWith(" OFF")));
        verify(f.target).commit();
    }
    @Test void postgresExplicitIdentityUsesOverrideClause() throws Exception {
        Fixture f=new Fixture(true); when(f.fields.isAutoIncrement(1)).thenReturn(true);
        f.run(UpdateCoreTest.task("a",List.of()),new UpdateExecution(60));
        assertTrue(f.commands.stream().anyMatch(sql -> sql.startsWith("INSERT INTO") && sql.contains("OVERRIDING SYSTEM VALUE")));
    }
    @Test void minimumRowsPreventsReplacingTarget()throws Exception {
        Fixture f=new Fixture(true);
        assertThrows(IllegalArgumentException.class,()->f.run(withPolicy("",2),new UpdateExecution(60)));
        assertEquals(-1,index(f.commands,"DELETE FROM"));verify(f.target).rollback();
    }
    @Test void changedDatasourceScopeStopsBeforeOpeningTarget()throws Exception {
        Fixture f=new Fixture(true);when(f.connections.schema("123456789012345678")).thenReturn("other_schema");
        assertThrows(IllegalArgumentException.class,()->f.run(UpdateCoreTest.task("a",List.of()),new UpdateExecution(60)));
        verify(f.connections,never()).open(anyString());
    }
    @Test void changedTargetStructureStopsBeforeSourceRead()throws Exception {
        Fixture f=new Fixture(true);ResultSet columns=mock(ResultSet.class),indexes=mock(ResultSet.class);
        when(f.targetMeta.getSearchStringEscape()).thenReturn("\\");
        when(f.targetMeta.getColumns(isNull(),eq("analytics"),anyString(),isNull())).thenReturn(columns);
        when(columns.next()).thenReturn(true,false);when(columns.getString("TABLE_NAME")).thenReturn("任意目标表");when(columns.getString("COLUMN_NAME")).thenReturn("id");when(columns.getString("TYPE_NAME")).thenReturn("INTEGER");when(columns.getInt("DATA_TYPE")).thenReturn(Types.INTEGER);
        when(f.targetMeta.getIndexInfo(isNull(),eq("analytics"),eq("任意目标表"),eq(false),eq(false))).thenReturn(indexes);
        var error=assertThrows(IllegalArgumentException.class,()->f.run(withPolicy("old-fingerprint",0),new UpdateExecution(60)));
        assertTrue(error.getMessage().contains("结构已变化"));verify(f.connections,never()).open("2");verify(f.target,never()).commit();
    }
    static UpdateDefinition withPolicy(String fingerprint,long minimum) {
        var t=UpdateCoreTest.task("a",List.of());
        return new UpdateDefinition(t.id(),t.version(),t.datasourceId(),t.schema(),t.table(),t.mode(),t.sourceDatasourceId(),t.sql(),t.strategy(),t.keys(),t.windowColumn(),t.windowDays(),t.allowEmpty(),t.trigger(),t.enabled(),t.cron(),t.timezone(),t.dependencies(),t.timeoutSeconds(),t.maxRows(),t.owner(),fingerprint,minimum);
    }
    static int index(List<String> sql, String prefix) { for(int i=0;i<sql.size();i++) if(sql.get(i).startsWith(prefix)) return i; return -1; }
    static UpdateDefinition withStrategy(UpdateDefinition.Strategy strategy,List<String> keys,boolean allowEmpty) {
        var t=UpdateCoreTest.task("a",List.of());
        return new UpdateDefinition(t.id(),t.version(),t.datasourceId(),t.schema(),t.table(),t.mode(),t.sourceDatasourceId(),t.sql(),strategy,keys,"amount",t.windowDays(),allowEmpty,t.trigger(),t.enabled(),t.cron(),t.timezone(),t.dependencies(),t.timeoutSeconds(),t.maxRows(),t.owner());
    }
}
