package io.dataease.company.bi.dataupdate;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QueryDecimalTest {
    private UpdateInspection.Column column(int precision,int scale) {
        return new UpdateInspection.Column("retailMoney",Types.DECIMAL,"DECIMAL",precision,scale,true,null,false);
    }
    @Test void expressionIntegerCapacityIsOnlyAWarning() {
        List<String> errors=new ArrayList<>(),warnings=new ArrayList<>();
        UpdateInspection.compare(List.of(column(29,2)),List.of(column(20,4)),UpdateCoreTest.task("a",List.of()),errors,warnings);
        assertTrue(errors.isEmpty());assertEquals(1,warnings.size());
        assertDoesNotThrow(()->UpdateInspection.validateDecimalValue(new BigDecimal("12345.67"),column(20,4),1));
    }
    @Test void insufficientFractionalScaleStillBlocks() {
        List<String> errors=new ArrayList<>();
        UpdateInspection.compare(List.of(column(29,5)),List.of(column(20,4)),UpdateCoreTest.task("a",List.of()),errors,new ArrayList<>());
        assertEquals(1,errors.size());
    }
    @Test void actualBoundsAndFractionalLossAreChecked() {
        for(String value:List.of("9999999999999999.9999","-9999999999999999.9999","0","1.230000"))
            assertDoesNotThrow(()->UpdateInspection.validateDecimalValue(new BigDecimal(value),column(20,4),1));
        for(String value:List.of("10000000000000000","-10000000000000000","1.23456","1E+20"))
            assertThrows(IllegalArgumentException.class,()->UpdateInspection.validateDecimalValue(new BigDecimal(value),column(20,4),2));
        assertDoesNotThrow(()->UpdateInspection.validateDecimalValue(BigDecimal.ZERO,column(4,4),1));
        assertDoesNotThrow(()->UpdateInspection.validateDecimalValue(null,column(20,4),1));
    }
    @Test void extractionOverflowFailsBeforeReplacingTarget() throws Exception {
        var f=new UpdateExecutorTest.Fixture(true);
        org.mockito.Mockito.when(f.fields.getColumnType(2)).thenReturn(Types.DECIMAL);
        org.mockito.Mockito.when(f.fields.getColumnTypeName(2)).thenReturn("DECIMAL");
        org.mockito.Mockito.when(f.fields.getPrecision(2)).thenReturn(20);
        org.mockito.Mockito.when(f.fields.getScale(2)).thenReturn(4);
        org.mockito.Mockito.when(f.data.getObject(2)).thenReturn(new BigDecimal("10000000000000000"));
        assertThrows(IllegalArgumentException.class,()->f.run(UpdateCoreTest.task("a",List.of()),new UpdateExecution(60)));
        org.mockito.Mockito.verify(f.target).rollback();
        org.mockito.Mockito.verify(f.target,org.mockito.Mockito.never()).commit();
        assertFalse(f.commands.stream().anyMatch(s->s.startsWith("DELETE FROM")));
    }
}
