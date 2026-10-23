package io.dataease.visualization.manage;

import io.dataease.api.visualization.request.DataVisualizationBaseRequest;
import io.dataease.api.visualization.vo.*;
import io.dataease.dataset.dao.auto.entity.*;
import io.dataease.dataset.dao.auto.mapper.*;
import io.dataease.dataset.manage.DatasetTableFieldManage;
import io.dataease.extensions.datasource.dto.*;
import io.dataease.extensions.view.dto.*;
import io.dataease.system.manage.CorePermissionManage;
import io.dataease.utils.*;
import io.dataease.visualization.calculation.DashboardCalculationLifecycle;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TemplateDerivedFieldRebindTest {
    @BeforeAll static void ids() {
        SnowFlake snow = mock(SnowFlake.class); AtomicLong next = new AtomicLong(900000000000000000L);
        when(snow.nextId()).thenAnswer(call -> next.incrementAndGet()); new IDUtils().setSnowFlake(snow);
    }
    static AppCoreDatasetTableFieldVO source(long id, int ext, String name, String origin) {
        var field = new AppCoreDatasetTableFieldVO(); field.setId(id); field.setExtField(ext);
        field.setName(name); field.setOriginName(origin); field.setDatasetGroupId(10L);
        field.setDatasetTableId(100L); field.setDatasourceId(1000L); field.setDeType(3);
        field.setGroupType("q"); field.setDataeaseName("f" + id); return field;
    }
    static DatasetTableFieldDTO target(long id, int ext, String name, String origin) {
        var field = BeanUtils.copyBean(new DatasetTableFieldDTO(), source(id, ext, name, origin));
        field.setDatasetGroupId(20L); field.setDatasetTableId(200L); field.setDatasourceId(2000L); return field;
    }
    static void inject(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    static List<DatasetTableFieldDTO> plan(List<AppCoreDatasetTableFieldVO> fields, List<DatasetTableFieldDTO> targets,
                                          Map<Long, Long> ids) {
        var required = new HashSet<Long>(); fields.forEach(field -> required.add(field.getId()));
        TemplateDerivedFieldRebind.expand(required, fields);
        return TemplateDerivedFieldRebind.plan(fields, required, Map.of(10L,20L), Map.of(100L,200L), Map.of(1000L,2000L),
                Map.of(10L, targets), new HashMap<>(), ids, new HashMap<>());
    }
    @Test void reusesNestedCalculatedFieldsWithDifferentIds() {
        var fields = List.of(source(1,0,"金额","amount"), source(2,2,"非零销售","IF([1]>0,[1],0)"), source(3,2,"客单价","[2]/10"));
        var ids = new HashMap<>(Map.of(1L,11L));
        assertTrue(plan(fields, List.of(target(12,2,"非零销售","IF([11]>0,[11],0)"),target(13,2,"客单价","[12]/10")),ids).isEmpty());
        assertEquals(13L, ids.get(3L));
    }
    @Test void createsMissingFieldsInDependencyOrderEvenWhenExportIsReversed() {
        var fields = List.of(source(3,2,"客单价","[2]/10"),source(2,2,"非零销售","[1]+1"),source(1,0,"金额","amount"));
        var ids = new HashMap<>(Map.of(1L,11L)); var created = plan(fields,List.of(),ids);
        assertEquals(2,created.size()); assertEquals("["+ids.get(2L)+"]/10", created.get(0).getOriginName());
        assertEquals("[11]+1",created.get(1).getOriginName()); assertEquals(20L,created.get(0).getDatasetGroupId());
    }
    @Test void groupingPreservesRulesAndRebindsItsBareSourceId() {
        var group = source(2,3,"金额分组","1"); group.setGroupList("[{\"name\":\"大额\",\"min\":\"100\",\"minTerm\":\"le\"}]"); group.setOtherGroup("其他");
        var ids = new HashMap<>(Map.of(1L,11L)); var created = plan(List.of(source(1,0,"金额","amount"),group), List.of(),ids);
        assertEquals("11",created.get(0).getOriginName()); assertEquals("大额",created.get(0).getGroupList().get(0).getName());
        assertEquals("其他",created.get(0).getOtherGroup());
        assertTrue(plan(List.of(source(1,0,"金额","amount"),group),created,new HashMap<>(Map.of(1L,11L))).isEmpty());
        created.get(0).setOtherGroup("不同");
        assertThrows(IllegalArgumentException.class,()->plan(List.of(source(1,0,"金额","amount"),group),created,new HashMap<>(Map.of(1L,11L))));
    }
    @Test void conflictsAndMissingDependenciesFailClosed() {
        var fields = List.of(source(1,0,"金额","amount"),source(2,2,"客单价","[1]/10"));
        assertThrows(IllegalArgumentException.class,()->plan(fields,List.of(target(12,2,"客单价","[11]/20")),new HashMap<>(Map.of(1L,11L))));
        assertThrows(IllegalArgumentException.class,()->plan(fields,List.of(),new HashMap<>()));
        assertThrows(IllegalArgumentException.class,()->plan(List.of(source(2,2,"损坏","[99]+1")),List.of(),new HashMap<>()));
        assertThrows(IllegalArgumentException.class,()->plan(List.of(source(2,2,"循环A","[3]"),source(3,2,"循环B","[2]")),List.of(),new HashMap<>()));
    }
    @Test void literalsAndNumbersAreNotDependenciesOrReplacementTargets() {
        assertEquals(Set.of(1L),DashboardCalculationLifecycle.references("IF([1]>1,'[123]',111)"));
        assertEquals("IF([11]>1,'[123]',111)",DashboardCalculationLifecycle.expression("IF([1]>1,'[123]',111)",Map.of(1L,11L,123L,456L)));
        String json = DashboardCalculationLifecycle.json("{\"extField\":3,\"originName\":\"1\",\"name\":\"1月\"}",Map.of(1L,11L),Map.of(),null);
        assertTrue(json.contains("\"originName\":\"11\"")); assertTrue(json.contains("1月"));
    }

    @Test void realRebindEntrySupportsSharedFieldsParametersAndPrivateExpressions() throws Exception {
        var manage = new Det2AppDatasetRebindV1Manage();
        var permissions = mock(CorePermissionManage.class); inject(manage,"corePermissionManage",permissions);
        when(permissions.checkAuth(any())).thenAnswer(call -> ((io.dataease.api.permissions.auth.dto.BusiPerCheckDTO)call.getArgument(0)).getAuthEnum() == io.dataease.constant.AuthEnum.READ);
        var fieldsManage = mock(DatasetTableFieldManage.class); var tables = mock(CoreDatasetTableMapper.class); var groups = mock(CoreDatasetGroupMapper.class);
        inject(manage,"datasetTableFieldManage",fieldsManage); inject(manage,"coreDatasetTableMapper",tables); inject(manage,"coreDatasetGroupMapper",groups);
        var targetTable = new CoreDatasetTable(); targetTable.setId(200L); targetTable.setDatasetGroupId(20L); targetTable.setDatasourceId(2000L);
        when(tables.selectList(any())).thenReturn(List.of(targetTable));
        var targetGroup = new CoreDatasetGroup(); targetGroup.setId(20L); targetGroup.setInfo("[{\"allChildCount\":0}]"); when(groups.selectById(20L)).thenReturn(targetGroup);
        when(fieldsManage.selectByDatasetGroupId(20L)).thenReturn(new ArrayList<>(List.of(target(11,0,"金额","amount"),target(12,2,"非零销售","[11]+1"))));
        var group = new AppCoreDatasetGroupVO(); group.setId(10L); group.setNodeType("dataset"); group.setSystemDatasetId(20L); group.setInfo("[{\"allChildCount\":0}]");
        var table = new AppCoreDatasetTableVO(); table.setId(100L); table.setDatasetGroupId(10L); table.setDatasourceId(1000L);
        var app = new VisualizationExport2AppVO(); app.setDatasetGroupsInfo(List.of(group)); app.setDatasetTablesInfo(List.of(table));
        app.setDatasetTableFieldsInfo(List.of(source(1,0,"金额","amount"),source(2,2,"非零销售","[1]+1")));
        var request = new DataVisualizationBaseRequest(); request.setComponentData("[{\"name\":\"1月\",\"checkedFieldsMap\":{\"50\":\"2\"}}]");
        request.setCalculationConfig("{\"parameters\":[{\"id\":\"90\",\"name\":\"日期\"}],\"datasetFields\":[{\"id\":\"91\",\"datasetGroupId\":\"10\",\"name\":\"共享\",\"expression\":\"[2]+[DVP.90]\"}]}");
        var chart = new ChartViewDTO(); chart.setId(50L); chart.setSourceTableId(10L);
        var privateField = new ChartViewFieldDTO(); privateField.setId(92L); privateField.setChartId(50L); privateField.setExtField(2); privateField.setOriginName("[91]+[2]");
        chart.setViewFields(List.of(privateField)); request.setCanvasViewInfo(new HashMap<>(Map.of(50L,chart)));
        String originalRequest = (String) JsonUtil.toJSONString(request);
        var fieldIds = new HashMap<Long,Long>(); var groupIds = new HashMap<Long,Long>();
        assertTrue(manage.tryRebind(app,request,groupIds,new HashMap<>(),fieldIds,new HashMap<>(),new HashMap<>()));
        var rebound = request.getCanvasViewInfo().get(50L); assertEquals(20L,rebound.getSourceTableId());
        assertEquals("[91]+[12]",rebound.getViewFields().get(0).getOriginName()); assertEquals(50L,rebound.getViewFields().get(0).getChartId());
        var config = DashboardCalculationLifecycle.rebind(request.getCalculationConfig(),groupIds,fieldIds);
        assertEquals("[12]+[DVP.90]",config.getDatasetFields().get(0).getExpression()); assertEquals(20L,config.getDatasetFields().get(0).getDatasetGroupId());
        assertTrue(request.getComponentData().contains("\"50\":\"12\"")); assertTrue(request.getComponentData().contains("1月"));
        verify(fieldsManage,never()).save(any(DatasetTableFieldDTO.class));

        // An absent target calculation is planned, but never written without dataset MANAGE.
        when(fieldsManage.selectByDatasetGroupId(20L)).thenReturn(new ArrayList<>(List.of(target(11,0,"金额","amount"))));
        assertThrows(RuntimeException.class,()->manage.tryRebind(app,JsonUtil.parseObject(originalRequest,DataVisualizationBaseRequest.class),
                new HashMap<>(),new HashMap<>(),new HashMap<>(),new HashMap<>(),new HashMap<>()));
        verify(fieldsManage,never()).save(any(DatasetTableFieldDTO.class));
        doReturn(true).when(permissions).checkAuth(any());
        var newRequest = JsonUtil.parseObject(originalRequest,DataVisualizationBaseRequest.class);
        assertTrue(manage.tryRebind(app,newRequest,new HashMap<>(),new HashMap<>(),new HashMap<>(),new HashMap<>(),new HashMap<>()));
        var saved = org.mockito.ArgumentCaptor.forClass(DatasetTableFieldDTO.class); verify(fieldsManage).save(saved.capture());
        assertEquals("[11]+1",saved.getValue().getOriginName()); assertEquals(20L,saved.getValue().getDatasetGroupId());
        assertEquals(20L,newRequest.getCanvasViewInfo().get(50L).getTableId());

        // The same physical column name on two joined tables must not be considered ambiguous.
        table.setTableName("orders"); targetTable.setTableName("orders");
        var otherTable = new AppCoreDatasetTableVO(); otherTable.setId(101L); otherTable.setDatasetGroupId(10L); otherTable.setDatasourceId(1000L); otherTable.setTableName("details");
        var otherTarget = new CoreDatasetTable(); otherTarget.setId(201L); otherTarget.setDatasetGroupId(20L); otherTarget.setDatasourceId(2000L); otherTarget.setTableName("details");
        when(tables.selectList(any())).thenReturn(List.of(targetTable,otherTarget)); app.setDatasetTablesInfo(List.of(table,otherTable));
        var otherField = target(21,0,"金额","amount"); otherField.setDatasetTableId(201L);
        when(fieldsManage.selectByDatasetGroupId(20L)).thenReturn(new ArrayList<>(List.of(target(11,0,"金额","amount"),otherField,target(12,2,"非零销售","[11]+1"))));
        fieldIds.clear();
        assertTrue(manage.tryRebind(app,JsonUtil.parseObject(originalRequest,DataVisualizationBaseRequest.class),new HashMap<>(),new HashMap<>(),fieldIds,new HashMap<>(),new HashMap<>()));
        assertEquals(11L,fieldIds.get(1L));

        // Exercise saveCanvas as well: its common import pass must not remap the graph twice.
        var server = new io.dataease.visualization.server.DataVisualizationServer();
        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        inject(server,"companyDashboardMutationSupport",provider); inject(server,"companyDashboardGuard",provider);
        inject(server,"det2AppDatasetRebindV1Manage",manage);
        var calculations = mock(io.dataease.visualization.calculation.DashboardCalculationManage.class);
        when(calculations.normalizeForCanvas(any())).thenAnswer(call -> io.dataease.visualization.calculation.DashboardCalculationManage.parse(((DataVisualizationBaseRequest)call.getArgument(0)).getCalculationConfig()));
        inject(server,"dashboardCalculationManage",calculations);
        var visualization = mock(CoreVisualizationManage.class); when(visualization.innerSave(any())).thenReturn(300L);
        inject(server,"coreVisualizationManage",visualization);
        var chartStorage = mock(io.dataease.chart.manage.ChartDataManage.class); inject(server,"chartDataManage",chartStorage);
        inject(server,"coreDatasetTableFieldMapper",mock(CoreDatasetTableFieldMapper.class));
        app.setLinkages(List.of()); app.setLinkageFields(List.of()); app.setLinkJumps(List.of()); app.setLinkJumpInfos(List.of());
        var saving = JsonUtil.parseObject(originalRequest,DataVisualizationBaseRequest.class); saving.setAppData(app); saving.setDataType("dataset");
        assertEquals("300",server.saveCanvas(saving));
        var savedConfig = io.dataease.visualization.calculation.DashboardCalculationManage.parse(saving.getCalculationConfig());
        assertEquals("[12]+[DVP.90]",savedConfig.getDatasetFields().get(0).getExpression());
        var chartCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(chartStorage).saveChartViewFromVisualization(any(),eq(300L),chartCaptor.capture());
        var storedChart = (ChartViewDTO) chartCaptor.getValue().get(50L);
        assertEquals(20L,storedChart.getTableId()); assertEquals("[91]+[12]",storedChart.getViewFields().get(0).getOriginName());
    }
}
