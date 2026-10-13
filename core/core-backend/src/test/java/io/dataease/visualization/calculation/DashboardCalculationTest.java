package io.dataease.visualization.calculation;

import io.dataease.api.visualization.dto.DashboardCalculationConfig;
import io.dataease.api.visualization.request.DataVisualizationBaseRequest;
import io.dataease.chart.dao.auto.entity.CoreChartView;
import io.dataease.chart.dao.auto.mapper.CoreChartViewMapper;
import io.dataease.company.bi.permission.CompanyDashboardGuard;
import io.dataease.dataset.dao.auto.entity.CoreDatasetGroup;
import io.dataease.dataset.dao.auto.mapper.CoreDatasetGroupMapper;
import io.dataease.dataset.manage.DatasetTableFieldManage;
import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import io.dataease.extensions.datasource.model.SQLObj;
import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.system.manage.CorePermissionManage;
import io.dataease.utils.JsonUtil;
import io.dataease.visualization.dao.auto.entity.DataVisualizationInfo;
import io.dataease.visualization.dao.auto.entity.SnapshotDataVisualizationInfo;
import io.dataease.visualization.dao.auto.mapper.DataVisualizationInfoMapper;
import io.dataease.visualization.dao.auto.mapper.SnapshotCoreChartViewMapper;
import io.dataease.visualization.dao.auto.mapper.SnapshotDataVisualizationInfoMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardCalculationTest {
    @Test void relativeDatesCoverCalendarBoundariesAndPersistAsRules() {
        var resolver = new DashboardParameterResolver();
        var rule = new DashboardCalculationConfig.RelativeDate();
        assertEquals(LocalDate.parse("2026-10-01"), resolver.relative(rule, LocalDate.parse("2026-10-12")));
        rule.setBase("YESTERDAY");
        assertEquals(LocalDate.parse("2026-09-01"), resolver.relative(rule, LocalDate.parse("2026-10-01")));
        rule.setBase("TODAY"); rule.setPosition("END"); rule.setOffset(-1);
        assertEquals(LocalDate.parse("2024-02-29"), resolver.relative(rule, LocalDate.parse("2024-03-31")));
        rule.setPeriod("WEEK");
        assertEquals(LocalDate.parse("2026-10-11"), resolver.relative(rule, LocalDate.parse("2026-10-12")));
        rule.setPeriod("QUARTER");
        assertEquals(LocalDate.parse("2025-12-31"), resolver.relative(rule, LocalDate.parse("2026-01-01")));
        rule.setPeriod("YEAR"); rule.setOffset(1); rule.setPosition("START");
        assertEquals(LocalDate.parse("2027-01-01"), resolver.relative(rule, LocalDate.parse("2026-12-31")));
        rule.setPeriod("DAY"); rule.setOffset(-2);
        assertEquals(LocalDate.parse("2025-12-30"), resolver.relative(rule, LocalDate.parse("2026-01-01")));
        var config = example();
        var definition = config.getParameters().get(0).getDefaultDefinition();
        definition.setMode("RELATIVE"); definition.setRelative(rule);
        config = DashboardCalculationManage.parse((String) JsonUtil.toJSONString(config));
        new DashboardCalculationValidator().validate(config, publicFields());
        assertEquals("2026-10-08", resolver.defaults(config, Instant.parse("2026-10-10T00:00:00Z")).get("900000000000000010"));
        rule.setOffset(Integer.MIN_VALUE);
        assertThrows(IllegalArgumentException.class, () -> resolver.relative(rule, LocalDate.now()));
        rule.setOffset(0); rule.setPeriod("INVALID");
        assertThrows(IllegalArgumentException.class, () -> resolver.relative(rule, LocalDate.now()));
    }
    static DashboardCalculationConfig example() {
        return DashboardCalculationManage.parse("""
            {"version":1,"timezone":"Asia/Shanghai","parameters":[
              {"id":"900000000000000010","name":"期初","type":"DATE","required":true,"default":{"mode":"FIXED","value":"2026-04-01"}},
              {"id":"900000000000000011","name":"期末","type":"DATE","required":true,"default":{"mode":"MACRO","expression":"{{{sunday last week}}}"}}
            ],"datasetFields":[{"id":"900000000000000012","datasetGroupId":"10","name":"YTD期间","groupType":"d","deType":2,
            "expression":"if(and([取值时间]>=[DYNAMIC_PARAMS.期初],[取值时间]<=[DYNAMIC_PARAMS.期末]),1,0)"}]}
            """);
    }

    static Map<Long, List<DatasetTableFieldDTO>> publicFields() {
        DatasetTableFieldDTO date = new DatasetTableFieldDTO();
        date.setId(11L); date.setName("取值时间"); date.setExtField(0); date.setDataeaseName("date_col");
        return Map.of(10L, List.of(date));
    }

    @Test void fixedMacroDefaultsShareTimezoneAndReferenceInstant() {
        var config = new DashboardCalculationValidator().validate(example(), publicFields());
        assertEquals(Map.of("900000000000000010", "2026-04-01", "900000000000000011", "2026-10-04"),
            new DashboardParameterResolver().defaults(config, Instant.parse("2026-10-10T00:00:00Z")));
        var resolver = new DashboardParameterResolver();
        assertEquals(LocalDate.parse("2026-09-28"), resolver.evaluate("{{{monday last week}}}", LocalDate.parse("2026-10-10")));
        assertEquals(LocalDate.parse("2026-09-01"), resolver.evaluate("{{{yesterday %yyyy-MM-01}}}", LocalDate.parse("2026-10-01")));
        config.getParameters().get(1).getDefaultDefinition().setExpression("{{{today}}}");
        assertEquals("2026-10-11", resolver.defaults(config, Instant.parse("2026-10-10T16:00:00Z")).get("900000000000000011"));
        assertThrows(IllegalArgumentException.class, () -> resolver.evaluate("{{{unsupported}}}", LocalDate.now()));
    }

    @Test void normalizedReferencesSurviveRenamesAndJsonRoundTrip() {
        var config = new DashboardCalculationValidator().validate(example(), publicFields());
        assertEquals("if(and([11]>=[DVP.900000000000000010],[11]<=[DVP.900000000000000011]),1,0)", config.getDatasetFields().get(0).getExpression());
        String json = (String) JsonUtil.toJSONString(config);
        assertTrue(json.contains("\"id\":\"900000000000000010\""));
        config = DashboardCalculationManage.parse(json);
        config.getParameters().get(0).setName("财年期初");
        var renamed = config;
        assertDoesNotThrow(() -> new DashboardCalculationValidator().validate(DashboardCalculationManage.parse((String) JsonUtil.toJSONString(renamed)), publicFields()));
    }

    @Test void invalidDependenciesDuplicatesCyclesAndCrossDatasetReferencesAreRejected() {
        var validator = new DashboardCalculationValidator();
        var missing = example(); missing.getParameters().remove(0);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(missing, publicFields()));
        var duplicate = example(); duplicate.getParameters().get(1).setName("期初");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(duplicate, publicFields()));
        var cyclic = example(); cyclic.getDatasetFields().get(0).setExpression("[YTD期间] + 1");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(cyclic, publicFields()));
        var foreign = example(); foreign.getDatasetFields().get(0).setExpression("[88]");
        DatasetTableFieldDTO other = new DatasetTableFieldDTO(); other.setId(88L); other.setName("别的数据集");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(foreign, Map.of(10L, publicFields().get(10L), 20L, List.of(other))));
        var literal = example(); literal.getDatasetFields().get(0).setExpression("if('[DYNAMIC_PARAMS.不存在]'='x',0,1)");
        assertDoesNotThrow(() -> validator.validate(literal, publicFields()));
        var badDate = example(); badDate.getParameters().get(0).getDefaultDefinition().setValue("2026-02-30");
        assertThrows(RuntimeException.class, () -> validator.validate(badDate, publicFields()));
    }

    @Test void parametersCannotTriggerSubstringFieldExpansion() throws Exception {
        var field = DashboardCalculationManage.toFields(new DashboardCalculationValidator().validate(example(), publicFields()), 100L, 10L).get(0);
        DatasetTableFieldDTO substring = new DatasetTableFieldDTO();
        substring.setId(9000000000000000L); substring.setExtField(2); substring.setOriginName("[9000000000000000]");
        SQLObj table = new SQLObj(); table.setTableAlias("t");
        String expression = io.dataease.engine.utils.Utils.buildCalcField(field, table,
            List.of(field, publicFields().get(10L).get(0), substring), 0, true, null, null, true, field.getOriginName());
        assertTrue(expression.contains("[DVP.900000000000000011]"));
        assertFalse(expression.contains("[11]"));
    }

    @Test void sameDatasetCardsShareOnlyTheirDashboardAndRevision() throws Exception {
        var manage = new DashboardCalculationManage();
        var chartMapper = mock(CoreChartViewMapper.class);
        var visualizations = mock(DataVisualizationInfoMapper.class);
        var snapshots = mock(SnapshotDataVisualizationInfoMapper.class);
        var snapshotCharts = mock(SnapshotCoreChartViewMapper.class);
        var guard = mock(CompanyDashboardGuard.class);
        ObjectProvider<CompanyDashboardGuard> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(guard);
        inject(manage, "chartMapper", chartMapper); inject(manage, "visualizationMapper", visualizations);
        inject(manage, "snapshotMapper", snapshots); inject(manage, "snapshotChartMapper", snapshotCharts); inject(manage, "dashboardGuard", provider);
        for (long id : List.of(1L, 2L, 3L)) {
            CoreChartView chart = new CoreChartView(); chart.setId(id); chart.setSceneId(id == 3L ? 200L : 100L); chart.setTableId(10L);
            when(chartMapper.selectById(id)).thenReturn(chart);
        }
        var config = new DashboardCalculationValidator().validate(example(), publicFields());
        DataVisualizationInfo a = new DataVisualizationInfo(); a.setCalculationConfig((String) JsonUtil.toJSONString(config));
        when(visualizations.selectById(100L)).thenReturn(a);
        when(visualizations.selectById(200L)).thenReturn(new DataVisualizationInfo());
        ChartViewDTO request = new ChartViewDTO();
        var first = manage.sharedFields(10L, 1L, request);
        assertEquals(first.get(0).getId(), manage.sharedFields(10L, 2L, request).get(0).getId());
        assertEquals(100L, first.get(0).getDashboardId());
        assertEquals("DASHBOARD", first.get(0).getFieldScope());
        assertTrue(manage.sharedFields(10L, 3L, request).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> manage.sharedFields(20L, 1L, request));
        request.setSceneId(200L);
        assertThrows(IllegalArgumentException.class, () -> manage.sharedFields(10L, 1L, request));
        request.setSceneId(100L); request.setResourceTable("snapshot");
        config.getDatasetFields().get(0).setName("未发布字段");
        SnapshotDataVisualizationInfo draft = new SnapshotDataVisualizationInfo(); draft.setCalculationConfig((String) JsonUtil.toJSONString(config));
        when(snapshots.selectById(100L)).thenReturn(draft);
        assertEquals("未发布字段", manage.sharedFields(10L, 4L, request).get(0).getName());
        var savedDraftChart = new io.dataease.visualization.dao.auto.entity.SnapshotCoreChartView();
        savedDraftChart.setId(4L); savedDraftChart.setSceneId(100L); savedDraftChart.setTableId(10L);
        when(snapshotCharts.selectById(4L)).thenReturn(savedDraftChart);
        assertTrue(manage.sharedFields(20L, 4L, request).isEmpty());
        request.setResourceTable("core");
        assertEquals("YTD期间", manage.sharedFields(10L, 1L, request).get(0).getName());
        verify(guard, atLeastOnce()).requireEdit(100L);
        request.setResourceTable("foreign");
        assertThrows(IllegalArgumentException.class, () -> manage.sharedFields(10L, 1L, request));
    }

    @Test void savingNormalizesChecksDatasetAccessAndPreservesOmittedConfig() throws Exception {
        var manage = new DashboardCalculationManage();
        var groups = mock(CoreDatasetGroupMapper.class); when(groups.selectById(10L)).thenReturn(new CoreDatasetGroup());
        var fields = mock(DatasetTableFieldManage.class); when(fields.listFieldsWithPermissions(10L)).thenReturn(publicFields().get(10L));
        var permissions = mock(CorePermissionManage.class); when(permissions.checkAuth(any())).thenReturn(true);
        ObjectProvider<CompanyDashboardGuard> provider = mock(ObjectProvider.class);
        var snapshots = mock(SnapshotDataVisualizationInfoMapper.class);
        inject(manage, "datasetGroupMapper", groups); inject(manage, "datasetTableFieldManage", fields);
        inject(manage, "corePermissionManage", permissions); inject(manage, "dashboardGuard", provider); inject(manage, "snapshotMapper", snapshots);
        DataVisualizationBaseRequest request = new DataVisualizationBaseRequest(); request.setId(100L);
        manage.prepareSave(request); assertNull(request.getCalculationConfig());
        request.setCalculationConfig((String) JsonUtil.toJSONString(example())); manage.prepareSave(request);
        assertTrue(request.getCalculationConfig().contains("[DVP.900000000000000010]"));
        when(permissions.checkAuth(any())).thenReturn(false);
        assertThrows(RuntimeException.class, () -> manage.prepareSave(request));
    }

    @Test void deletingDependenciesIsBlockedAtChartFormulaAndAxis() {
        Set<Long> removed = Set.of(123L);
        assertThrows(RuntimeException.class, () -> DashboardCalculationManage.rejectRemovedReferences("sum([123])", removed));
        assertThrows(RuntimeException.class, () -> DashboardCalculationManage.rejectRemovedReferences("[DVP.123]", removed));
        assertThrows(RuntimeException.class, () -> DashboardCalculationManage.rejectRemovedReferences("{\"id\":\"123\"}", removed));
        assertDoesNotThrow(() -> DashboardCalculationManage.rejectRemovedReferences("{\"id\":\"1234\"}", removed));
    }

    static void inject(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
