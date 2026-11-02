package io.dataease.visualization.manage;

import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import io.dataease.extensions.view.dto.ChartViewFieldDTO;
import org.junit.Assert;
import org.junit.Test;

public class Det2AppDatasetRebindV1ManageTest {

    @Test
    public void comparisonDateIsActiveEvenWithoutDateAxis() {
        var chart = new io.dataease.extensions.view.dto.ChartViewDTO();
        var metric = io.dataease.utils.JsonUtil.parseObject(
                "{\"id\":\"50\",\"compareCalc\":{\"type\":\"year_yoy\",\"field\":\"81\"}}",
                ChartViewFieldDTO.class);
        chart.setYAxis(java.util.List.of(metric));
        Assert.assertEquals(java.util.Set.of(50L, 81L),
                manage.collectActiveFieldIds(java.util.Map.of(1L, chart), "[]"));
        metric.getCompareCalc().setType("none");
        Assert.assertEquals(java.util.Set.of(50L),
                manage.collectActiveFieldIds(java.util.Map.of(1L, chart), "[]"));
    }

    @Test
    public void comparisonReferencesRemapWithoutChangingLiteralFields() throws Exception {
        String source = "{\"field\":\"81\",\"yAxis\":[{\"id\":\"50\",\"compareCalc\":{\"type\":\"year_yoy\",\"field\":\"81\"}},{\"compareCalc\":{\"type\":\"month_yoy\",\"field\":81}}]}";
        var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                io.dataease.visualization.calculation.DashboardCalculationLifecycle.json(
                        source, java.util.Map.of(50L, 150L, 81L, 181L), java.util.Map.of(), null));
        Assert.assertEquals("81", result.path("field").asText());
        Assert.assertEquals("150", result.path("yAxis").get(0).path("id").asText());
        Assert.assertEquals("181", result.path("yAxis").get(0).path("compareCalc").path("field").asText());
        Assert.assertEquals(181L, result.path("yAxis").get(1).path("compareCalc").path("field").asLong());
    }

    @Test
    public void candidateCatalogDoesNotMakeUnusedFieldsActive() {
        var chart = new io.dataease.extensions.view.dto.ChartViewDTO();
        var date = placement(81L, "日期", "d", "d", null, null);
        var channel = placement(82L, "渠道", "d", "d", null, null);
        chart.setViewFields(java.util.List.of(date, channel));
        chart.setXAxis(java.util.List.of(channel));
        Assert.assertEquals(java.util.Set.of(82L),
                manage.collectActiveFieldIds(java.util.Map.of(1L, chart), "[]"));
        chart.setXAxis(java.util.List.of(date));
        Assert.assertEquals(java.util.Set.of(81L),
                manage.collectActiveFieldIds(java.util.Map.of(1L, chart), "[]"));
        Assert.assertFalse(manage.isDeTypeCompatible(0, 1));
    }

    private final Det2AppDatasetRebindV1Manage manage = new Det2AppDatasetRebindV1Manage();

    @Test
    public void isDeTypeCompatible_numericCrossTypes() {
        Assert.assertTrue(manage.isDeTypeCompatible(2, 3));
        Assert.assertTrue(manage.isDeTypeCompatible(3, 2));
    }

    @Test
    public void isDeTypeCompatible_sameType() {
        Assert.assertTrue(manage.isDeTypeCompatible(0, 0));
        Assert.assertTrue(manage.isDeTypeCompatible(1, 1));
    }

    @Test
    public void isDeTypeCompatible_textVsNumeric() {
        Assert.assertFalse(manage.isDeTypeCompatible(0, 2));
    }

    @Test
    public void isGroupTypeCompatible_dqCross() {
        Assert.assertTrue(manage.isGroupTypeCompatible("d", "q"));
        Assert.assertTrue(manage.isGroupTypeCompatible("q", "d"));
    }

    @Test
    public void rebuildChartField_preservesPlacementAndTargetMetadata() {
        ChartViewFieldDTO source = new ChartViewFieldDTO();
        source.setId(100L);
        source.setGroupType("q");
        source.setSummary("sum");
        source.setFieldInstanceId("inst-1");
        source.setMetricInstanceId("metric-1");
        source.setChartShowName("展示名");

        DatasetTableFieldDTO target = new DatasetTableFieldDTO();
        target.setId(200L);
        target.setDataeaseName("f_actual_money");
        target.setOriginName("actualMoney");
        target.setDeType(2);
        target.setGroupType("q");
        target.setDatasetGroupId(999L);
        target.setDatasetTableId(888L);
        target.setDatasourceId(777L);

        ChartViewFieldDTO rebuilt = manage.rebuildChartField(source, target);

        Assert.assertEquals(Long.valueOf(200L), rebuilt.getId());
        Assert.assertEquals("f_actual_money", rebuilt.getDataeaseName());
        Assert.assertEquals("actualMoney", rebuilt.getOriginName());
        Assert.assertEquals(Integer.valueOf(2), rebuilt.getDeType());
        Assert.assertEquals("sum", rebuilt.getSummary());
        Assert.assertEquals("q", rebuilt.getGroupType());
        Assert.assertEquals("inst-1", rebuilt.getFieldInstanceId());
        Assert.assertEquals("metric-1", rebuilt.getMetricInstanceId());
        Assert.assertEquals("展示名", rebuilt.getChartShowName());
        Assert.assertNull(rebuilt.getChartId());
    }

    @Test
    public void rebuildChartField_keepsMeasurePlacementWhenTargetDefaultIsDimension() {
        ChartViewFieldDTO source = placement(101L, "channel", "d", "q", "count", "fi-a");
        DatasetTableFieldDTO target = datasetField(201L, "channel", "d");

        ChartViewFieldDTO rebuilt = manage.rebuildChartField(source, target);

        Assert.assertEquals(Long.valueOf(201L), rebuilt.getId());
        Assert.assertEquals("q", rebuilt.getGroupType());
        Assert.assertEquals("count", rebuilt.getSummary());
        Assert.assertEquals("fi-a", rebuilt.getFieldInstanceId());
        Assert.assertEquals("channel", rebuilt.getOriginName());
    }

    @Test
    public void rebuildChartField_keepsDimensionPlacementWhenTargetDefaultIsMeasure() {
        ChartViewFieldDTO source = placement(102L, "amount", "q", "d", null, "fi-b");
        DatasetTableFieldDTO target = datasetField(202L, "amount", "q");

        ChartViewFieldDTO rebuilt = manage.rebuildChartField(source, target);

        Assert.assertEquals(Long.valueOf(202L), rebuilt.getId());
        Assert.assertEquals("d", rebuilt.getGroupType());
        Assert.assertNull(rebuilt.getSummary());
        Assert.assertEquals("fi-b", rebuilt.getFieldInstanceId());
    }

    @Test
    public void rebuildChartField_samePhysicalFieldKeepsIndependentPlacements() {
        DatasetTableFieldDTO target = datasetField(203L, "channel", "d");
        ChartViewFieldDTO dim = placement(103L, "channel", "d", "d", null, "inst-d");
        ChartViewFieldDTO quota = placement(103L, "channel", "d", "q", "count", "inst-q");

        ChartViewFieldDTO rebuiltDim = manage.rebuildChartField(dim, target);
        ChartViewFieldDTO rebuiltQuota = manage.rebuildChartField(quota, target);

        Assert.assertEquals("d", rebuiltDim.getGroupType());
        Assert.assertEquals("q", rebuiltQuota.getGroupType());
        Assert.assertEquals("count", rebuiltQuota.getSummary());
        Assert.assertEquals("inst-d", rebuiltDim.getFieldInstanceId());
        Assert.assertEquals("inst-q", rebuiltQuota.getFieldInstanceId());
        Assert.assertNotEquals(rebuiltDim.getFieldInstanceId(), rebuiltQuota.getFieldInstanceId());
    }

    private static ChartViewFieldDTO placement(
            Long fieldId, String originName, String datasetDefaultRole, String placementRole, String summary, String instanceId) {
        ChartViewFieldDTO dto = new ChartViewFieldDTO();
        dto.setId(fieldId);
        dto.setOriginName(originName);
        dto.setName(originName);
        dto.setGroupType(placementRole);
        dto.setSummary(summary);
        dto.setFieldInstanceId(instanceId);
        dto.setDeType("q".equals(datasetDefaultRole) ? 3 : 0);
        return dto;
    }

    private static DatasetTableFieldDTO datasetField(Long id, String originName, String defaultRole) {
        DatasetTableFieldDTO dto = new DatasetTableFieldDTO();
        dto.setId(id);
        dto.setOriginName(originName);
        dto.setName(originName);
        dto.setDataeaseName("f_" + originName);
        dto.setGroupType(defaultRole);
        dto.setDeType("q".equals(defaultRole) ? 3 : 0);
        return dto;
    }
}
