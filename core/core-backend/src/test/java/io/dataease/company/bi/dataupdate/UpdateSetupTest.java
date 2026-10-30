package io.dataease.company.bi.dataupdate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.Types;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdateSetupTest {
    @Test void singleTableAndItsIndexesAreAccepted(){assertEquals(2,UpdateDdl.validate("CREATE TABLE `bi`.`任意表` (id BIGINT NOT NULL, amount DECIMAL(18,2), PRIMARY KEY(id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4; CREATE INDEX idx_amount ON bi.`任意表`(amount)","bi","任意表").size());}
    @Test void literalsDoNotBecomeStatements(){assertDoesNotThrow(()->UpdateDdl.validate("CREATE TABLE t(id INT COMMENT 'DROP TABLE x;') ENGINE=InnoDB","bi","t"));}
    @Test void ifNotExistsStillIdentifiesTarget(){assertDoesNotThrow(()->UpdateDdl.validate("CREATE TABLE IF NOT EXISTS t(id INT)","bi","t"));}
    @ParameterizedTest @ValueSource(strings={
        "DROP TABLE t", "CREATE TABLE other(id INT)", "CREATE TABLE elsewhere.t(id INT)",
        "CREATE TABLE t AS SELECT * FROM source", "CREATE TABLE t LIKE source", "CREATE TEMPORARY TABLE t(id INT)",
        "CREATE TABLE t(id INT); INSERT INTO t VALUES(1)", "CREATE TABLE t(id INT); CREATE INDEX idx ON other(id)",
        "CREATE TABLE t(id INT); CREATE TABLE other(id INT)", "CREATE TABLE t(id INT) ENGINE=FEDERATED CONNECTION='remote'",
        "CREATE TABLE t(id INT REFERENCES other(id))", "CREATE TABLE t(id INT) DATA DIRECTORY='/tmp'"})
    void rejectsNonLocalOrNonInitializationSql(String sql){assertThrows(IllegalArgumentException.class,()->UpdateDdl.validate(sql,"bi","t"));}
    static UpdateInspection.Column column(String name,int type,int size,int scale,boolean nullable,String defaultValue){return new UpdateInspection.Column(name,type,"type",size,scale,nullable,defaultValue,false);}
    @Test void missingRequiredTargetColumnIsBlocked(){
        var errors=new ArrayList<String>();UpdateInspection.compare(List.of(column("id",Types.INTEGER,10,0,false,null)),List.of(column("id",Types.INTEGER,10,0,false,null),column("amount",Types.DECIMAL,18,2,false,null)),UpdateCoreTest.task("a",List.of()),errors,new ArrayList<>());
        assertTrue(errors.stream().anyMatch(message->message.contains("amount")));
    }
    @Test void targetDefaultsPermitOmittedColumns(){
        var errors=new ArrayList<String>();UpdateInspection.compare(List.of(column("id",Types.INTEGER,10,0,false,null)),List.of(column("id",Types.INTEGER,10,0,false,null),column("amount",Types.DECIMAL,18,2,false,"0")),UpdateCoreTest.task("a",List.of()),errors,new ArrayList<>());assertTrue(errors.isEmpty());
    }
    @Test void precisionAndStringTruncationAreBlocked(){
        var errors=new ArrayList<String>();UpdateInspection.compare(List.of(column("amount",Types.DECIMAL,20,4,false,null),column("name",Types.VARCHAR,200,0,true,null)),List.of(column("amount",Types.DECIMAL,18,2,false,null),column("name",Types.VARCHAR,20,0,true,null)),UpdateCoreTest.task("a",List.of()),errors,new ArrayList<>());assertEquals(2,errors.size());
    }
    @Test void unknownStringToNumberNeedsExplicitCast(){
        var errors=new ArrayList<String>();UpdateInspection.compare(List.of(column("id",Types.VARCHAR,20,0,true,null)),List.of(column("id",Types.INTEGER,10,0,false,null)),UpdateCoreTest.task("a",List.of()),errors,new ArrayList<>());assertFalse(errors.isEmpty());
    }
    @Test void duplicateQueryLabelsAreBlocked(){
        var errors=new ArrayList<String>();var id=column("id",Types.INTEGER,10,0,false,null);UpdateInspection.compare(List.of(id,id),List.of(id),UpdateCoreTest.task("a",List.of()),errors,new ArrayList<>());assertTrue(errors.getFirst().contains("重复"));
    }
    @Test void groupOrderIncludesSharedInputOnlyOnce(){
        Map<String,UpdateDefinition> tasks=new LinkedHashMap<>();tasks.put("last",UpdateCoreTest.task("last",List.of("left","right")));tasks.put("right",UpdateCoreTest.task("right",List.of("source")));tasks.put("left",UpdateCoreTest.task("left",List.of("source")));tasks.put("source",UpdateCoreTest.task("source",List.of()));
        var ordered=UpdateGroups.ordered(tasks);assertEquals(4,ordered.size());assertEquals("source",ordered.getFirst().id());assertEquals("last",ordered.getLast().id());
    }
    @Test void groupCannotUseMissingOrOtherBatchInputs(){assertThrows(IllegalArgumentException.class,()->UpdateGroups.ordered(Map.of("task",UpdateCoreTest.task("task",List.of("outside")))));}
    @Test void groupRejectsCycles(){assertThrows(IllegalArgumentException.class,()->UpdateGroups.ordered(Map.of("a",UpdateCoreTest.task("a",List.of("b")),"b",UpdateCoreTest.task("b",List.of("a")))));}
}
