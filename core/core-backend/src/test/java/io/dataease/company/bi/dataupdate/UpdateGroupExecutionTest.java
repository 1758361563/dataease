package io.dataease.company.bi.dataupdate;

import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdateGroupExecutionTest {
    static class Fixture implements AutoCloseable {
        final UpdateGroups groups=mock(UpdateGroups.class);final UpdateRuntime runtime=mock(UpdateRuntime.class);final UpdateExecutor executor=mock(UpdateExecutor.class);
        final DataSource metadata=mock(DataSource.class);final Connection connection=mock(Connection.class);final PreparedStatement statement=mock(PreparedStatement.class);final ResultSet result=mock(ResultSet.class);
        final UpdateGroupRuntime runner=new UpdateGroupRuntime(groups,runtime,executor,metadata,mock(Scheduler.class));
        final UpdateDefinition a=UpdateCoreTest.task("a",List.of()),b=UpdateCoreTest.task("b",List.of("a")),c=UpdateCoreTest.task("c",List.of("b"));
        final UpdateGroups.Pending pending=new UpdateGroups.Pending("batch","2026-10-30","QUEUED",new UpdateGroups.Snapshot(new UpdateGroups.Definition("g",1,a.datasourceId(),"test",List.of("a","b","c"),false,"0 0 3 * * ?","Asia/Shanghai","local"),List.of(a,b,c)));
        Fixture()throws Exception{when(metadata.getConnection()).thenReturn(connection);when(connection.prepareStatement(anyString())).thenReturn(statement);when(statement.executeQuery()).thenReturn(result);when(result.next()).thenReturn(true);when(result.getInt(1)).thenReturn(1);when(result.getBoolean(1)).thenReturn(true);when(groups.start("batch")).thenReturn(true);when(executor.execute(any(),any(),any())).thenReturn(1L);}
        public void close(){runner.close();}
    }
    @Test void successfulBatchExecutesInPinnedOrderAndReleasesClaims()throws Exception{
        try(var f=new Fixture()){f.runner.execute(f.pending);var order=inOrder(f.executor);order.verify(f.executor).execute(eq(f.a),any(),any());order.verify(f.executor).execute(eq(f.b),any(),any());order.verify(f.executor).execute(eq(f.c),any(),any());verify(f.groups).finish("batch","SUCCESS","本批全部完成",true);}
    }
    @Test void failedNodeBlocksAllLaterNodes()throws Exception{
        try(var f=new Fixture()){when(f.executor.execute(eq(f.b),any(),any())).thenThrow(new SQLException("failure","42000"));f.runner.execute(f.pending);verify(f.executor,never()).execute(eq(f.c),any(),any());verify(f.groups).finish(eq("batch"),eq("FAILED"),anyString(),eq(true));}
    }
    @Test void cancelledBatchDoesNotExecuteAnyNodes()throws Exception{
        try(var f=new Fixture()){when(f.groups.cancelled("batch")).thenReturn(true);f.runner.execute(f.pending);verifyNoInteractions(f.executor);verify(f.groups).finish(eq("batch"),eq("CANCELLED"),anyString(),eq(true));}
    }
    @Test void unavailableResourceLocksKeepQueueWaiting()throws Exception{
        try(var f=new Fixture()){when(f.result.getInt(1)).thenReturn(0);f.runner.execute(f.pending);verify(f.groups,never()).start(anyString());verifyNoInteractions(f.executor);}
    }
    @Test void lostLockRetainsClaimsForReview()throws Exception{
        try(var f=new Fixture()){when(f.result.getBoolean(1)).thenReturn(false);f.runner.execute(f.pending);verifyNoInteractions(f.executor);verify(f.groups).finish(eq("batch"),eq("NEEDS_REVIEW"),anyString(),eq(false));}
    }
    @Test void allResourceLocksUseOneMetadataConnection()throws Exception{
        try(var f=new Fixture();var lock=UpdateLock.acquireMany(f.metadata,List.of("c","a","b"))){assertNotNull(lock);verify(f.metadata,times(1)).getConnection();verify(f.connection,times(3)).prepareStatement("SELECT GET_LOCK(?,0)");}
    }
}
