package io.dataease.company.bi.dataupdate;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpdateDeletionTest {
    @Test void deleteRemovesBoundSourceAndTaskWithoutDroppingBusinessTable() {
        var db=mock(JdbcTemplate.class);var tx=mock(TransactionTemplate.class);
        doAnswer(call->{((Consumer<TransactionStatus>)call.getArgument(0)).accept(mock(TransactionStatus.class));return null;}).when(tx).executeWithoutResult(any());
        var store=spy(new UpdateStore(db,tx));var task=UpdateCoreTest.task("task",List.of());
        doReturn(task).when(store).get("task");doReturn(false).when(store).claimed(task);
        doReturn(false).when(store).grouped("task");doReturn(List.of(task)).when(store).all();
        when(db.queryForObject(anyString(),eq(Integer.class),eq("task"))).thenReturn(0);
        Runnable audit=mock(Runnable.class);
        store.delete("task",1,audit);
        verify(audit).run();
        verify(db).update("DELETE FROM company_data_update_task WHERE id=?","task");
        verify(db).update("DELETE FROM company_feishu_source WHERE managed_task_id=?","task");
        verify(db,never()).execute(anyString());
        doReturn(true).when(store).claimed(task);
        clearInvocations(db,audit);
        assertThrows(IllegalArgumentException.class,()->store.delete("task",1,audit));
        verify(db,never()).update(anyString(),any(Object[].class));verifyNoInteractions(audit);
    }
}
