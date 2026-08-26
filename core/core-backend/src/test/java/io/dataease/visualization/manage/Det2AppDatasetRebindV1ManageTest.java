package io.dataease.visualization.manage;

import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import io.dataease.extensions.view.dto.ChartViewFieldDTO;
import org.junit.Assert;
import org.junit.Test;

public class Det2AppDatasetRebindV1ManageTest {

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
        Assert.assertEquals("inst-1", rebuilt.getFieldInstanceId());
        Assert.assertEquals("metric-1", rebuilt.getMetricInstanceId());
        Assert.assertEquals("展示名", rebuilt.getChartShowName());
        Assert.assertNull(rebuilt.getChartId());
    }
}
