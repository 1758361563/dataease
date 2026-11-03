package io.dataease.company.bi.dataupdate;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SheetValuesTest {
    private final SheetSource.Field field=new SheetSource.Field("目标","目标",false,"");
    private UpdateInspection.Column column(int jdbc,String type,int precision,int scale,boolean nullable) {
        return new UpdateInspection.Column("目标",jdbc,type,precision,scale,nullable,null,false);
    }
    @Test void decimalsPreservePrecisionAndRejectRounding() {
        var target=column(Types.DECIMAL,"DECIMAL",18,6,true);
        assertEquals(new BigDecimal("17.798595"),SheetValues.convert("17.798595",field,target,List.of(),2));
        assertThrows(IllegalArgumentException.class,()->SheetValues.convert("17.7985951",field,target,List.of(),2));
        assertThrows(IllegalArgumentException.class,()->SheetValues.convert("99999999999999",field,target,List.of(),2));
    }
    @Test void nullSentinelsAreExplicitAndRequiredValuesStayRequired() {
        var target=column(Types.DATE,"DATE",0,0,true);
        assertThrows(IllegalArgumentException.class,()->SheetValues.convert("None",field,target,List.of(),2));
        assertNull(SheetValues.convert("None",field,target,List.of("None"),2));
        assertThrows(IllegalArgumentException.class,()->SheetValues.convert("None",new SheetSource.Field("目标","目标",true,""),target,List.of("None"),2));
    }
    @Test void invalidDatesAndIntegerTruncationFail() {
        assertThrows(IllegalArgumentException.class,()->SheetValues.convert("2026-02-30",field,column(Types.DATE,"DATE",0,0,true),List.of(),5));
        assertThrows(IllegalArgumentException.class,()->SheetValues.convert("1.2",field,column(Types.INTEGER,"INT",10,0,true),List.of(),5));
        assertThrows(IllegalArgumentException.class,()->SheetValues.convert("128",field,column(Types.TINYINT,"TINYINT",3,0,true),List.of(),5));
        assertEquals(java.sql.Date.valueOf("2026-11-02"),SheetValues.convert("2026/11/02",new SheetSource.Field("目标","目标",false,"yyyy/MM/dd"),column(Types.DATE,"DATE",0,0,true),List.of(),5));
    }
    @Test void textLimitsAndUnknownColumnsFail() {
        assertThrows(IllegalArgumentException.class,()->SheetValues.convert("abc",field,column(Types.VARCHAR,"VARCHAR",2,0,true),List.of(),3));
        var config=source(List.of(field));
        assertThrows(IllegalArgumentException.class,()->SheetValues.mapping(config,List.of("其他"),new UpdateInspection.Structure(List.of(column(Types.INTEGER,"INT",10,0,true)),List.of(),"x")));
    }
    @Test void sourceRejectsWrongHostsAndDuplicateMappings() {
        source(List.of(field)).validate(true);
        assertThrows(IllegalArgumentException.class,()->source(List.of(field,field)).validate(true));
        var bad=new SheetSource(null,"任意来源","default","https://evil.test/sheets/abcd","abc","Sheet1",1,2,null,"A:B",List.of(field),List.of());
        assertThrows(IllegalArgumentException.class,()->bad.validate(true));
    }
    private SheetSource source(List<SheetSource.Field> fields) {
        return new SheetSource(null,"任意来源","default","https://example.feishu.cn/sheets/abcd","abc","Sheet1",1,2,null,"A:B",fields,List.of());
    }
    @Test void sheetConfigurationSurvivesTaskAndBatchSerialization() {
        var task=new UpdateDefinition(null,0,"123","db","target",UpdateDefinition.Mode.FEISHU,"123","",UpdateDefinition.Strategy.FULL,List.of(),"",1,false,UpdateDefinition.Trigger.MANUAL,false,"","Asia/Shanghai",List.of(),60,1000,"test","fingerprint",1,source(List.of(field)));
        var saved=task.normalized("id",1,"instance");saved.validate();
        assertEquals(saved,UpdateStore.definition(UpdateStore.json(saved)));
        assertEquals(saved.sheet(),saved.withSchema("other").sheet());
    }
}
