package io.dataease.company.bi.dataupdate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpdateCoreTest {
    static UpdateDefinition task(String id, List<String> dependencies) {
        return new UpdateDefinition(id, 1, "123456789012345678", "analytics", "任意目标表", UpdateDefinition.Mode.QUERY, "2",
                "SELECT id, amount FROM orders", UpdateDefinition.Strategy.FULL, List.of(), "", 7, false,
                UpdateDefinition.Trigger.MANUAL, false, "0 0 3 * * ?", "Asia/Shanghai", dependencies, 60, 10000, "local");
    }
    @Test void arbitraryTablesAndSourcesAreSupported() { assertDoesNotThrow(() -> task(null, List.of()).validate()); }
    @Test void graphRejectsCycles() {
        assertThrows(IllegalArgumentException.class, () -> UpdateDefinition.validateGraph(Map.of("a",task("a",List.of("b")),"b",task("b",List.of("a")))));
    }
    @Test void graphRejectsMissingParent() { assertThrows(IllegalArgumentException.class, () -> UpdateDefinition.validateGraph(Map.of("a",task("a",List.of("missing"))))); }
    @Test void diamondDependencyIsValid() {
        assertDoesNotThrow(() -> UpdateDefinition.validateGraph(Map.of("a",task("a",List.of()),"b",task("b",List.of("a")),"c",task("c",List.of("a")),"d",task("d",List.of("b","c")))));
    }
    @Test void taskSerializesWithoutLosingDatasourcePrecision() { var task=task("a",List.of()); assertEquals(task, UpdateStore.definition(UpdateStore.json(task))); }
    @Test void timezoneDeterminesBusinessDate() { assertEquals(LocalDate.of(2026,10,30),task("a",List.of()).businessDate(Instant.parse("2026-10-29T17:00:00Z"))); }
    @Test void windowsAreHalfOpenAndNotFixedToAnExample() {
        assertEquals("SELECT '2026-02-26 00:00:00', '2026-03-01 00:00:00' FROM `表`", UpdateSql.render("SELECT {{window_start}}, {{window_end}} FROM {{target_table}}", LocalDate.of(2026,3,1),3,"`表`"));
    }
    @Test void unknownTemplateFails() { assertThrows(IllegalArgumentException.class,()->UpdateSql.render("SELECT {{secret}}",LocalDate.now(),1,"t")); }
    @Test void lexicalSplitterPreservesLiteralsAndIdentifiers() {
        assertEquals(List.of("SELECT 'a;b', 'it''s;ok', [x;y], `a;b`, \"z;z\"", "SELECT 2"),UpdateSql.statements("-- ;\n SELECT 'a;b', 'it''s;ok', [x;y], `a;b`, \"z;z\"; /* ; */ SELECT 2;"));
    }
    @Test void nestedCommentsAndDollarStringsStayTogether() {
        assertEquals(List.of("SELECT $$a;b$$", "SELECT $tag$x;y$tag$"),UpdateSql.statements("SELECT $$a;b$$; /*outer /*inner*/ end*/ SELECT $tag$x;y$tag$;"));
    }
    @ParameterizedTest @ValueSource(strings={"SELECT 'oops", "SELECT [oops", "SELECT 1 /*oops", "SELECT $$oops", "COMMIT;", "START TRANSACTION;", "SELECT 1\nGO\nSELECT 2", "DELIMITER $$", "/*! DROP TABLE x */ SELECT 1", "SELECT 'a\\'b'"})
    void invalidScriptsFailBeforeExecution(String sql) { assertThrows(IllegalArgumentException.class,()->UpdateSql.statements(sql)); }
    @ParameterizedTest @ValueSource(strings={"DELETE FROM t", "SELECT 1; DELETE FROM t", "WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x", "SELECT * INTO other FROM t", "SELECT * FROM t FOR UPDATE", "SELECT * FROM t INTO OUTFILE '/tmp/x'"})
    void extractionRejectsWrites(String sql) { assertThrows(IllegalArgumentException.class,()->UpdateSql.requireQuery(sql)); }
    @Test void extractionAllowsCtesAndQuotedKeywords() {
        assertDoesNotThrow(()->UpdateSql.requireQuery("WITH x AS (SELECT 'DELETE' AS \"update\") SELECT * FROM x"));
    }
    @Test void identifiersAreEscapedByDialect() {
        assertEquals("`a``b`",UpdateConnections.Dialect.MYSQL.quote("a`b"));
        assertEquals("[a]]b]",UpdateConnections.Dialect.SQLSERVER.quote("a]b"));
        assertEquals("\"a\"\"b\"",UpdateConnections.Dialect.POSTGRES.quote("a\"b"));
    }
    @Test void sqlServerStagingDoesNotInheritIdentity() { assertTrue(UpdateConnections.Dialect.SQLSERVER.createStage("[#stage]","[t]").contains("CROSS JOIN")); }
    @Test void cancellationCancelsRegisteredStatements() throws Exception {
        Statement statement=mock(Statement.class); UpdateExecution execution=new UpdateExecution(60); execution.watch(statement);
        execution.stop("用户取消"); verify(statement).cancel(); assertThrows(SQLException.class,execution::check);
    }
    @Test void statementsRegisteredAfterCancellationAreClosed() throws Exception {
        Statement statement=mock(Statement.class); UpdateExecution execution=new UpdateExecution(60); execution.stop("取消");
        assertThrows(SQLException.class,()->execution.watch(statement)); verify(statement).close();
    }
    @Test void databaseErrorsDoNotExposeSqlOrCredentials() {
        String message=UpdateRuntime.error(new SQLException("password=secret; SELECT private_data", "08001", 123));
        assertFalse(message.contains("secret")); assertFalse(message.contains("private_data")); assertTrue(message.contains("08001"));
    }
    @Test void duplicateColumnsFail() throws Exception {
        ResultSetMetaData meta=mock(ResultSetMetaData.class); when(meta.getColumnCount()).thenReturn(2); when(meta.getColumnLabel(anyInt())).thenReturn("id");
        assertThrows(IllegalArgumentException.class,()->UpdateExecutor.labels(meta));
    }
}
