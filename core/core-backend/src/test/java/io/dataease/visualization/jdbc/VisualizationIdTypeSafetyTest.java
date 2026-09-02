package io.dataease.visualization.jdbc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VisualizationIdTypeSafetyTest {

    @Test
    void adjacentLargeIdsStayDistinctAsExactStrings() {
        assertEquals("900000000000000010", VisualizationIds.exact(900000000000000010L));
        assertEquals("900000000000000011", VisualizationIds.exact(900000000000000011L));
        assertNotEquals(VisualizationIds.exact(900000000000000010L), VisualizationIds.exact(900000000000000011L));
    }

    @Test
    void varcharLongTypeHandlerParsesExactDecimalString() {
        assertEquals(Long.valueOf(900000000000000010L), VarcharLongTypeHandler.parse("900000000000000010"));
        assertEquals(Long.valueOf(900000000000000011L), VarcharLongTypeHandler.parse("900000000000000011"));
        assertNull(VarcharLongTypeHandler.parse(null));
        assertNull(VarcharLongTypeHandler.parse(" "));
    }
}
