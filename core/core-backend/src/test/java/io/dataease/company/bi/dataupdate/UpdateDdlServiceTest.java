package io.dataease.company.bi.dataupdate;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpdateDdlServiceTest {
    static final UpdateDdlService.Request REQUEST=new UpdateDdlService.Request("00000000-0000-4000-8000-000000000001","2","t","CREATE TABLE t(id INT); CREATE INDEX idx ON t(id)");
    static class Fixture {
        final DataSource metadata=mock(DataSource.class);final Connection lockConnection=mock(Connection.class),target=mock(Connection.class);
        final PreparedStatement lockStatement=mock(PreparedStatement.class),existsStatement=mock(PreparedStatement.class);
        final ResultSet lockResult=mock(ResultSet.class),existsResult=mock(ResultSet.class);final DatabaseMetaData targetMeta=mock(DatabaseMetaData.class);
        final Statement command=mock(Statement.class);final UpdateConnections connections=mock(UpdateConnections.class);final UpdateInspection inspection=mock(UpdateInspection.class);
        final JdbcTemplate db=mock(JdbcTemplate.class);final TransactionTemplate tx=mock(TransactionTemplate.class);
        final Map<String,Object> operation=new LinkedHashMap<>();boolean present=false,released=false;int completed=0;
        final UpdateDdlService service=new UpdateDdlService(metadata,connections,inspection,db,tx);
        Fixture()throws Exception {
            operation.put("id",REQUEST.operationId());operation.put("datasource_id","2");operation.put("table_name","t");operation.put("status","RUNNING");
            when(metadata.getConnection()).thenReturn(lockConnection);when(lockConnection.prepareStatement(anyString())).thenReturn(lockStatement);when(lockStatement.executeQuery()).thenReturn(lockResult);when(lockResult.next()).thenReturn(true);when(lockResult.getInt(1)).thenReturn(1);when(lockResult.getBoolean(1)).thenReturn(true);
            when(connections.open("2")).thenReturn(UpdateExecutorTest.Fixture.handle(target));when(connections.schema("2")).thenReturn("bi");when(target.getMetaData()).thenReturn(targetMeta);when(targetMeta.getDatabaseProductName()).thenReturn("MySQL");when(target.prepareStatement(anyString())).thenReturn(existsStatement);when(existsStatement.executeQuery()).thenReturn(existsResult);when(existsResult.next()).thenReturn(true);when(target.createStatement()).thenReturn(command);
            when(inspection.structure("2","t")).thenReturn(new UpdateInspection.Structure(List.of(),List.of(),"fingerprint"));
            doAnswer(invocation->{Consumer<TransactionStatus> callback=invocation.getArgument(0);callback.accept(mock(TransactionStatus.class));return null;}).when(tx).executeWithoutResult(any());
            when(db.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(0);
            when(db.queryForList(anyString(),any(Object[].class))).thenAnswer(invocation->{
                String sql=invocation.getArgument(0);
                if(sql.startsWith("SELECT sql_hash"))return present?List.of(Map.of("sql_hash",UpdateStore.hash(REQUEST.sql()),"target_key",UpdateStore.hash(UpdateStore.json(List.of("2","t"))))):List.of();
                return present?List.of(operation):List.of();
            });
            when(db.update(anyString(),any(Object[].class))).thenAnswer(invocation->{
                String sql=invocation.getArgument(0);
                if(sql.startsWith("INSERT INTO company_data_update_ddl"))present=true;
                if(sql.contains("SET completed_steps"))completed=invocation.getArgument(1);
                if(sql.contains("SET status='SUCCESS'"))operation.put("status","SUCCESS");
                if(sql.contains("SET status='NEEDS_REVIEW'"))operation.put("status","NEEDS_REVIEW");
                if(sql.startsWith("DELETE FROM company_data_update_claim"))released=true;
                return 1;
            });
        }
    }
    @Test void successfulDdlReadsStructureAndReleasesClaim()throws Exception {
        Fixture f=new Fixture();var result=f.service.create(REQUEST,()->{});assertEquals("SUCCESS",result.get("status"));assertEquals(2,f.completed);assertTrue(f.released);verify(f.inspection).structure("2","t");
    }
    @Test void indexFailureRetainsClaimAndReportsPartialProgress()throws Exception {
        Fixture f=new Fixture();when(f.command.execute(startsWith("CREATE INDEX"))).thenThrow(new SQLException("failure","42000"));var result=f.service.create(REQUEST,()->{});assertEquals("NEEDS_REVIEW",result.get("status"));assertEquals(1,f.completed);assertFalse(f.released);
    }
    @Test void sameOperationReturnsRecordedStatusWithoutExecutingAgain()throws Exception {
        Fixture f=new Fixture();f.present=true;assertEquals("RUNNING",f.service.create(REQUEST,()->fail("must not audit twice")).get("status"));verifyNoInteractions(f.connections);verify(f.metadata,never()).getConnection();
    }
    @Test void reusedOperationIdCannotChangeSql()throws Exception {
        Fixture f=new Fixture();f.present=true;assertThrows(IllegalArgumentException.class,()->f.service.create(new UpdateDdlService.Request(REQUEST.operationId(),"2","t","CREATE TABLE t(x INT)"),()->{}));verifyNoInteractions(f.connections);
    }
    @Test void existingTableIsNotSilentlyAcceptedAsNew()throws Exception {
        Fixture f=new Fixture();when(f.existsResult.getInt(1)).thenReturn(1);assertThrows(IllegalArgumentException.class,()->f.service.create(REQUEST,()->{}));verifyNoInteractions(f.command);assertFalse(f.present);
    }
}
