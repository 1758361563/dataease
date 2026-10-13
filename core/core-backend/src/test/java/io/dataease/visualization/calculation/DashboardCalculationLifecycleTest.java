package io.dataease.visualization.calculation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.api.visualization.request.DataVisualizationBaseRequest;
import io.dataease.chart.dao.auto.entity.CoreChartView;
import io.dataease.chart.dao.auto.mapper.CoreChartViewMapper;
import io.dataease.company.bi.permission.CompanyDashboardGuard;
import io.dataease.dataset.dao.auto.entity.CoreDatasetTableField;
import io.dataease.dataset.dao.auto.mapper.CoreDatasetTableFieldMapper;
import io.dataease.dataset.utils.TableUtils;
import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.extensions.view.dto.ChartExtRequest;
import io.dataease.utils.JsonUtil;
import io.dataease.visualization.dao.auto.entity.DataVisualizationInfo;
import io.dataease.visualization.dao.auto.entity.SnapshotCoreChartView;
import io.dataease.visualization.dao.auto.mapper.*;
import io.dataease.visualization.dao.ext.mapper.ExtDataVisualizationMapper;
import io.dataease.visualization.manage.CoreVisualizationManage;
import io.dataease.visualization.server.DataVisualizationServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardCalculationLifecycleTest {
    @BeforeAll static void ids() {
        var snowflake = mock(io.dataease.utils.SnowFlake.class);
        var sequence = new java.util.concurrent.atomic.AtomicLong(910000000000000000L);
        when(snowflake.nextId()).thenAnswer(call -> sequence.incrementAndGet());
        new io.dataease.utils.IDUtils().setSnowFlake(snowflake);
    }
    private String config() {
        return (String) JsonUtil.toJSONString(new DashboardCalculationValidator().validate(DashboardCalculationTest.example(), DashboardCalculationTest.publicFields()));
    }

    @Test void copiesHaveIndependentIdsWhileKeepingDefaultsAndExpressions() {
        String original = config(); var first = DashboardCalculationLifecycle.fresh(original);
        var second = DashboardCalculationLifecycle.fresh(original);
        assertNotEquals(first.config().getParameters().get(0).getId(), second.config().getParameters().get(0).getId());
        assertEquals("2026-04-01", first.config().getParameters().get(0).getDefaultDefinition().getValue());
        assertTrue(first.config().getDatasetFields().get(0).getExpression().contains("[DVP." + first.config().getParameters().get(0).getId() + "]"));
        assertEquals(original, config());
    }

    @Test void encodedAxesPrivateReferencesAndAliasesRemapWithoutChangingNamesOrDates() throws Exception {
        var ids = Map.of(11L, 101L, 12L, 102L);
        String encoded = Base64.getEncoder().encodeToString("[DVP.11]+[12]+'[11]'".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String source = "{\"xAxis\":\"[{\\\"id\\\":\\\"12\\\",\\\"originName\\\":\\\"[12]\\\"}]\",\"fieldId\":\"11,12\",\"name\":\"YTD11\",\"date\":\"2026-11-12\",\"originName\":\"" + encoded + "\",\"dataeaseName\":\"old\",\"dashboardParameterValues\":{\"11\":\"2026-01-01\"}}";
        var result = new ObjectMapper().readTree(DashboardCalculationLifecycle.json(source, ids, Map.of("old", "new"), 200L));
        var axis = new ObjectMapper().readTree(result.get("xAxis").asText()).get(0);
        assertEquals("102", axis.get("id").asText()); assertEquals("[102]", axis.get("originName").asText());
        assertEquals("101,102", result.get("fieldId").asText()); assertEquals("YTD11", result.get("name").asText());
        assertEquals("2026-11-12", result.get("date").asText()); assertEquals("new", result.get("dataeaseName").asText());
        assertFalse(result.has("dashboardParameterValues"));
        assertEquals("[DVP.101]+[102]+'[11]'", new String(Base64.getDecoder().decode(result.get("originName").asText()), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void applicationDatasetRebindingTouchesOnlyExactFieldReferences() {
        var mapped = DashboardCalculationLifecycle.rebind(config(), Map.of(10L, 20L), Map.of(11L, 22L));
        assertEquals(20L, mapped.getDatasetFields().get(0).getDatasetGroupId());
        assertTrue(mapped.getDatasetFields().get(0).getExpression().contains("[22]"));
        assertTrue(mapped.getDatasetFields().get(0).getExpression().contains("[DVP.900000000000000011]"));
    }

    @Test void copyEntryClonesPrivateFieldsAndPublishesMatchingSnapshotMetadata() throws Exception {
        var server = new DataVisualizationServer();
        var visualizations = mock(DataVisualizationInfoMapper.class); var charts = mock(CoreChartViewMapper.class);
        var snapshots = mock(SnapshotCoreChartViewMapper.class); var fields = mock(CoreDatasetTableFieldMapper.class);
        var ext = mock(ExtDataVisualizationMapper.class); var manage = mock(CoreVisualizationManage.class);
        ObjectProvider provider = mock(ObjectProvider.class);
        DashboardCalculationTest.inject(server, "companyDashboardMutationSupport", provider);
        DashboardCalculationTest.inject(server, "visualizationInfoMapper", visualizations);
        DashboardCalculationTest.inject(server, "coreChartViewMapper", charts);
        DashboardCalculationTest.inject(server, "snapshotCoreChartViewMapper", snapshots);
        DashboardCalculationTest.inject(server, "coreDatasetTableFieldMapper", fields);
        DashboardCalculationTest.inject(server, "extDataVisualizationMapper", ext);
        DashboardCalculationTest.inject(server, "coreVisualizationManage", manage);
        DashboardCalculationTest.inject(server, "dashboardCalculationCopyRelations", mock(DashboardCalculationCopyRelations.class));
        var source = new DataVisualizationInfo(); source.setId(100L); source.setCalculationConfig(config()); source.setComponentData("[{\"id\":\"1\",\"name\":\"Card1\"}]");
        when(visualizations.selectById(100L)).thenReturn(source);
        var copied = new CoreChartView(); copied.setId(2L); copied.setCopyFrom(1L);
        copied.setxAxis("[{\"id\":\"900000000000000012\",\"dataeaseName\":\"" + TableUtils.fieldNameShort("900000000000000012") + "\"}]");
        when(ext.findViewInfoByCopyId(anyLong())).thenReturn(List.of(copied));
        var privateField = new CoreDatasetTableField(); privateField.setId(500L); privateField.setChartId(1L);
        privateField.setOriginName("[DVP.900000000000000010]+[900000000000000012]"); privateField.setDataeaseName("private_old");
        when(fields.selectList(any())).thenReturn(List.of(privateField));
        var request = new DataVisualizationBaseRequest(); request.setId(100L); request.setPid(-1L); request.setName("Copy");
        server.copy(request);
        var fieldCaptor = ArgumentCaptor.forClass(CoreDatasetTableField.class); verify(fields).insert(fieldCaptor.capture());
        assertEquals(2L, fieldCaptor.getValue().getChartId()); assertNotEquals(500L, fieldCaptor.getValue().getId());
        assertFalse(fieldCaptor.getValue().getOriginName().contains("[DVP.900000000000000010]"));
        var snapshotCaptor = ArgumentCaptor.forClass(SnapshotCoreChartView.class); verify(snapshots).insert(snapshotCaptor.capture());
        assertFalse(snapshotCaptor.getValue().getxAxis().contains("900000000000000012"));
        var visualizationCaptor = ArgumentCaptor.forClass(DataVisualizationInfo.class); verify(manage).preInnerSave(visualizationCaptor.capture());
        assertTrue(visualizationCaptor.getValue().getComponentData().contains("Card1"));
        assertTrue(visualizationCaptor.getValue().getComponentData().contains("\"id\":\"2\""));
    }

    @Test void exportRequestValuesAreUsedAndConfiguredQueriesBypassLegacyCache() throws Exception {
        var manage = new DashboardCalculationManage(); var charts = mock(CoreChartViewMapper.class); var visualizations = mock(DataVisualizationInfoMapper.class);
        ObjectProvider<CompanyDashboardGuard> provider = mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(mock(CompanyDashboardGuard.class));
        DashboardCalculationTest.inject(manage, "chartMapper", charts); DashboardCalculationTest.inject(manage, "visualizationMapper", visualizations);
        DashboardCalculationTest.inject(manage, "dashboardGuard", provider);
        var chart = new CoreChartView(); chart.setSceneId(100L); chart.setTableId(10L); when(charts.selectById(1L)).thenReturn(chart);
        var board = new DataVisualizationInfo(); board.setCalculationConfig(config()); when(visualizations.selectById(100L)).thenReturn(board);
        var request = new ChartViewDTO(); request.setId(1L); request.setSceneId(100L); request.setTableId(10L);
        var ext = new ChartExtRequest(); ext.setDashboardParameterValues(Map.of("900000000000000011", "2026-09-30")); ext.setDashboardParameterReference(Instant.parse("2026-10-10T00:00:00Z").toString()); request.setChartExtRequest(ext);
        var queryFields = DashboardCalculationManage.toFields(DashboardCalculationManage.parse(config()), 100L, 10L);
        manage.bindQuery(request, queryFields);
        assertFalse(ext.isCache()); assertTrue(queryFields.get(0).getOriginName().contains("2026-09-30"));
    }

    @Test void unboundTemplatesKeepDefinitionsButCannotBypassLiveDatasetChecks() throws Exception {
        var manage = new DashboardCalculationManage();
        var groups = mock(io.dataease.dataset.dao.auto.mapper.CoreDatasetGroupMapper.class);
        DashboardCalculationTest.inject(manage, "datasetGroupMapper", groups);
        var request = new DataVisualizationBaseRequest(); request.setCalculationConfig(config());
        var template = new ChartViewDTO(); template.setDataFrom("template"); template.setSourceTableId(10L);
        var date = new io.dataease.extensions.view.dto.ChartViewFieldDTO(); date.setId(11L); date.setName("取值时间");
        template.setViewFields(List.of(date)); request.setCanvasViewInfo(Map.of(1L, template));
        assertEquals(1, manage.normalizeForCanvas(request).getDatasetFields().size());
        template.setTableId(10L); template.setDataFrom("calc");
        assertThrows(RuntimeException.class, () -> manage.normalizeForCanvas(request));
    }

    @Test void linkageAndSelfJumpGraphsKeepMatchingLiveAndDraftIds() throws Exception {
        var relations = new DashboardCalculationCopyRelations();
        var parents = mock(VisualizationLinkageMapper.class); var drafts = mock(SnapshotVisualizationLinkageMapper.class);
        var fields = mock(VisualizationLinkageFieldMapper.class); var draftFields = mock(SnapshotVisualizationLinkageFieldMapper.class);
        var jumps = mock(VisualizationLinkJumpMapper.class); var draftJumps = mock(SnapshotVisualizationLinkJumpMapper.class);
        var infos = mock(VisualizationLinkJumpInfoMapper.class); var draftInfos = mock(SnapshotVisualizationLinkJumpInfoMapper.class);
        var targets = mock(VisualizationLinkJumpTargetViewInfoMapper.class); var draftTargets = mock(SnapshotVisualizationLinkJumpTargetViewInfoMapper.class);
        var dependencies = Map.of("linkages", parents, "draftLinkages", drafts, "linkageFields", fields,
                "draftLinkageFields", draftFields, "jumps", jumps, "draftJumps", draftJumps,
                "jumpInfos", infos, "draftJumpInfos", draftInfos, "jumpTargets", targets, "draftJumpTargets", draftTargets);
        for (var dependency : dependencies.entrySet()) DashboardCalculationTest.inject(relations, dependency.getKey(), dependency.getValue());
        var parent = new io.dataease.visualization.dao.auto.entity.VisualizationLinkage(); parent.setId(50L); parent.setDvId(100L); parent.setSourceViewId(1L); parent.setTargetViewId(2L);
        var field = new io.dataease.visualization.dao.auto.entity.VisualizationLinkageField(); field.setId(60L); field.setLinkageId(50L); field.setSourceField(10L); field.setTargetField(10L);
        var jump = new io.dataease.visualization.dao.auto.entity.VisualizationLinkJump(); jump.setId(70L); jump.setSourceDvId(100L); jump.setSourceViewId(1L);
        var info = new io.dataease.visualization.dao.auto.entity.VisualizationLinkJumpInfo(); info.setId(80L); info.setLinkJumpId(70L); info.setSourceFieldId(10L); info.setTargetDvId(100L);
        var target = new io.dataease.visualization.dao.auto.entity.VisualizationLinkJumpTargetViewInfo(); target.setTargetId(90L); target.setLinkJumpInfoId(80L); target.setSourceFieldActiveId(10L); target.setTargetViewId("2"); target.setTargetFieldId("10");
        when(parents.selectList(any())).thenReturn(List.of(parent)); when(fields.selectList(any())).thenReturn(List.of(field));
        when(jumps.selectList(any())).thenReturn(List.of(jump)); when(infos.selectList(any())).thenReturn(List.of(info)); when(targets.selectList(any())).thenReturn(List.of(target));
        relations.copy(100L, 200L, 300L, false, Map.of(1L, 3L, 2L, 4L), new DashboardCalculationLifecycle.Remapped(new io.dataease.api.visualization.dto.DashboardCalculationConfig(), Map.of(10L, 20L), Map.of()));
        var p = ArgumentCaptor.forClass(io.dataease.visualization.dao.auto.entity.VisualizationLinkage.class); verify(parents).insert(p.capture());
        var f = ArgumentCaptor.forClass(io.dataease.visualization.dao.auto.entity.VisualizationLinkageField.class); verify(fields).insert(f.capture());
        var df = ArgumentCaptor.forClass(io.dataease.visualization.dao.auto.entity.SnapshotVisualizationLinkageField.class); verify(draftFields).insert(df.capture());
        assertEquals(200L, p.getValue().getDvId()); assertEquals(3L, p.getValue().getSourceViewId());
        assertEquals(p.getValue().getId(), f.getValue().getLinkageId()); assertEquals(20L, f.getValue().getSourceField());
        assertEquals(f.getValue().getId(), df.getValue().getId()); assertEquals(f.getValue().getLinkageId(), df.getValue().getLinkageId());
        var ji = ArgumentCaptor.forClass(io.dataease.visualization.dao.auto.entity.VisualizationLinkJumpInfo.class); verify(infos).insert(ji.capture());
        var jt = ArgumentCaptor.forClass(io.dataease.visualization.dao.auto.entity.VisualizationLinkJumpTargetViewInfo.class); verify(targets).insert(jt.capture());
        assertEquals(200L, ji.getValue().getTargetDvId()); assertEquals(20L, ji.getValue().getSourceFieldId());
        assertEquals(ji.getValue().getId(), jt.getValue().getLinkJumpInfoId()); assertEquals("4", jt.getValue().getTargetViewId()); assertEquals("20", jt.getValue().getTargetFieldId());
        assertEquals(100L, parent.getDvId()); assertEquals(10L, field.getSourceField());
        verify(drafts, never()).selectList(any());
    }

    @Test void unpublishedRelationsUseSnapshotAsTheirSource() throws Exception {
        var relations = new DashboardCalculationCopyRelations();
        var live = mock(VisualizationLinkageMapper.class); var drafts = mock(SnapshotVisualizationLinkageMapper.class);
        DashboardCalculationTest.inject(relations, "linkages", live); DashboardCalculationTest.inject(relations, "draftLinkages", drafts);
        DashboardCalculationTest.inject(relations, "draftLinkageFields", mock(SnapshotVisualizationLinkageFieldMapper.class));
        DashboardCalculationTest.inject(relations, "draftJumps", mock(SnapshotVisualizationLinkJumpMapper.class));
        var source = new io.dataease.visualization.dao.auto.entity.SnapshotVisualizationLinkage(); source.setId(50L); source.setDvId(100L); source.setSourceViewId(1L);
        when(drafts.selectList(any())).thenReturn(List.of(source));
        relations.copy(100L, 200L, 300L, true, Map.of(1L, 2L), new DashboardCalculationLifecycle.Remapped(new io.dataease.api.visualization.dto.DashboardCalculationConfig(), Map.of(), Map.of()));
        var captured = ArgumentCaptor.forClass(io.dataease.visualization.dao.auto.entity.VisualizationLinkage.class); verify(live).insert(captured.capture());
        assertEquals(200L, captured.getValue().getDvId()); assertEquals(2L, captured.getValue().getSourceViewId());
        verify(live, never()).selectList(any());
    }

    @Test void exportedTemplateObjectsActuallyDecompressWithFreshReferencesAndPrivateFields() throws Exception {
        var server = new DataVisualizationServer(); ObjectProvider provider = mock(ObjectProvider.class);
        DashboardCalculationTest.inject(server, "companyDashboardMutationSupport", provider);
        DashboardCalculationTest.inject(server, "companyDashboardGuard", provider);
        var resource = DataVisualizationServer.class.getDeclaredField("staticResourceServer");
        DashboardCalculationTest.inject(server, "staticResourceServer", mock(resource.getType()));
        DashboardCalculationTest.inject(server, "templateExtendDataMapper", mock(io.dataease.template.dao.auto.mapper.VisualizationTemplateExtendDataMapper.class));
        var request = new DataVisualizationBaseRequest(); request.setNewFrom("new_outer_template"); request.setName("Template"); request.setType("dashboard"); request.setCalculationConfig(config());
        request.setCanvasStyleData("{\"component\":{}}");
        request.setComponentData("[{\"id\":\"1\",\"name\":\"Card1\",\"fieldId\":\"900000000000000012\",\"checkedFields\":[\"1\"],\"checkedFieldsMap\":{\"1\":\"900000000000000012\"}}]");
        request.setDynamicData("{\"1\":{\"id\":\"1\",\"tableId\":\"10\",\"viewFields\":[{\"id\":\"11\",\"name\":\"取值时间\",\"deType\":1},{\"id\":\"14\",\"chartId\":\"1\",\"name\":\"Private\",\"extField\":2,\"dataeaseName\":\"private\",\"originName\":\"[DVP.900000000000000010]+[11]\"}],\"dashboardParameterValues\":{\"900000000000000010\":\"2025-01-01\"}}}");
        var result = server.decompression(request);
        var chart = result.getCanvasViewInfo().values().iterator().next();
        var definitions = DashboardCalculationManage.parse(result.getCalculationConfig());
        assertNotEquals(900000000000000010L, definitions.getParameters().get(0).getId());
        assertNotEquals(14L, chart.getViewFields().get(1).getId()); assertEquals(chart.getId(), chart.getViewFields().get(1).getChartId());
        assertTrue(chart.getViewFields().get(1).getOriginName().contains("[DVP." + definitions.getParameters().get(0).getId() + "]"));
        assertNull(chart.getDashboardParameterValues());
        var component = new ObjectMapper().readTree(result.getComponentData()).get(0);
        assertEquals("Card1", component.get("name").asText()); assertEquals(chart.getId().toString(), component.get("id").asText());
        assertEquals(chart.getId().toString(), component.get("checkedFields").get(0).asText());
        assertEquals(definitions.getDatasetFields().get(0).getId().toString(), component.get("checkedFieldsMap").get(chart.getId().toString()).asText());
    }

    @Test void parameterApplicationsAvoidThePhysicalOnlyRebindPath() {
        var manage = new io.dataease.visualization.manage.Det2AppDatasetRebindV1Manage();
        var request = new DataVisualizationBaseRequest(); request.setCalculationConfig(config());
        var app = new io.dataease.api.visualization.vo.VisualizationExport2AppVO();
        assertFalse(manage.tryRebind(app, request, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>()));
    }
}
