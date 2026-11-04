package io.dataease.dataset.manage;

import org.junit.jupiter.api.Test;
import java.sql.Statement;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreviewExecutionTest {
    @Test void oversizedPagesFailRatherThanSilentlyTruncating() {
        try (var run = PreviewExecution.open(null, 1, false, 101)) {
            PreviewExecution.acceptRow(new String[]{"a".repeat(600_000), null});
            assertThrows(IllegalArgumentException.class,
                    () -> PreviewExecution.acceptRow(new String[]{"a".repeat(500_000)}));
        }
        assertDoesNotThrow(() -> PreviewExecution.acceptRow(new String[]{"a".repeat(1_100_000)}));
    }
    @Test void appliesLimitsAndCancelsOnlyTheOwnersStatement() throws Exception {
        String id = UUID.randomUUID().toString();
        Statement statement = mock(Statement.class);
        when(statement.getQueryTimeout()).thenReturn(3);
        try (var run = PreviewExecution.open(id, 7, false, 101);
             var binding = PreviewExecution.bind(statement)) {
            verify(statement).setQueryTimeout(3);
            verify(statement).setMaxRows(101);
            PreviewExecution.cancel(id, 8);
            verify(statement, never()).cancel();
            run.check();
            PreviewExecution.cancel(id, 7);
            verify(statement).cancel();
            assertThrows(IllegalArgumentException.class, run::check);
        }
        clearInvocations(statement);
        PreviewExecution.cancel(id, 7);
        verifyNoInteractions(statement);
    }

    @Test void cancellationBeforeRegistrationDoesNotExecuteSql() throws Exception {
        String id = UUID.randomUUID().toString();
        PreviewExecution.cancel(id, 7);
        Statement statement = mock(Statement.class);
        try (var run = PreviewExecution.open(id, 7, false, 101)) {
            assertThrows(IllegalArgumentException.class, () -> PreviewExecution.bind(statement));
            verifyNoInteractions(statement);
        }
    }

    @Test void countLaneIsBoundedAndReleasedOnFailure() throws Exception {
        try (var run = PreviewExecution.open(null, 1, true, 1)) {
            assertThrows(IllegalArgumentException.class, () -> PreviewExecution.open(null, 2, true, 1));
            // Detail capacity is independent of counts.
            try (var detail = PreviewExecution.open(null, 2, false, 101)) { detail.check(); }
        }
        Statement statement = mock(Statement.class);
        try (var run = PreviewExecution.open(null, 1, true, 1);
             var binding = PreviewExecution.bind(statement)) {
            verify(statement).setQueryTimeout(5);
            verify(statement).setMaxRows(1);
        }
        // Outside a preview scope, exports/chart calls retain their own JDBC settings.
        clearInvocations(statement);
        try (var binding = PreviewExecution.bind(statement)) { verifyNoInteractions(statement); }
    }
}
