package io.dataease.chart.server;

import io.dataease.api.chart.request.ChartExcelRequest;
import io.dataease.chart.constant.ChartConstants;
import io.dataease.chart.manage.ChartDataManage;
import io.dataease.chart.utils.ChartDataBuild;
import io.dataease.constant.DeTypeConstants;
import io.dataease.exportCenter.manage.ExportCenterLimitManage;
import io.dataease.exportCenter.util.ExportCenterUtils;
import io.dataease.extensions.view.dto.ChartViewFieldDTO;
import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.extensions.view.dto.FormatterCfgDTO;
import org.junit.Test;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ChartDataServerTest {

    @Test
    public void findExcelDataUsesConfiguredViewExportLimitWhenViewUsesCustomResultCount() throws Exception {
        ChartDataServer chartDataServer = chartDataServerWithExportLimits(1_000_000L, 1_000_000L);

        ChartViewDTO view = new ChartViewDTO();
        view.setResultMode(ChartConstants.VIEW_RESULT_MODE.CUSTOM);
        view.setResultCount(100_000);

        ChartExcelRequest request = new ChartExcelRequest();
        request.setDownloadType("view");
        request.setViewInfo(view);

        chartDataServer.findExcelData(request);

        assertEquals(Integer.valueOf(1_000_000), view.getResultCount());
    }

    @Test
    public void findExcelDataUsesViewExportLimitForViewDownloads() throws Exception {
        ChartDataServer chartDataServer = chartDataServerWithExportLimits(1_000_000L, 100_000L);

        ChartViewDTO view = new ChartViewDTO();
        view.setResultMode(ChartConstants.VIEW_RESULT_MODE.ALL);
        view.setResultCount(100_000);

        ChartExcelRequest request = new ChartExcelRequest();
        request.setDownloadType("view");
        request.setViewInfo(view);

        chartDataServer.findExcelData(request);

        assertEquals(Integer.valueOf(1_000_000), view.getResultCount());
    }

    @Test
    public void excelExportLimitUsesLowerLimitForDatasetDownloads() throws Exception {
        ChartDataServer chartDataServer = chartDataServerWithExportLimits(1_000_000L, 100_000L);

        int resultCount = ReflectionTestUtils.invokeMethod(chartDataServer, "getExcelExportLimit", "dataset");

        assertEquals(100_000, resultCount);
    }

    @Test
    public void findExcelDataStoresCalculatedFieldsOnViewInfo() throws Exception {
        ChartDataServer chartDataServer = chartDataServerWithExportLimits(1_000_000L, 1_000_000L);

        ChartViewDTO view = new ChartViewDTO();
        view.setResultMode(ChartConstants.VIEW_RESULT_MODE.ALL);

        ChartExcelRequest request = new ChartExcelRequest();
        request.setDownloadType("view");
        request.setViewInfo(view);

        chartDataServer.findExcelData(request);

        assertNotNull(view.getData());
        assertEquals(1, ((List<?>) view.getData().get("fields")).size());
    }

    @Test
    public void setExcelDataUsesCurrentDrillFieldOrderForNumericFormats() {
        ChartViewFieldDTO province = field("province", "省份", DeTypeConstants.DE_STRING);
        ChartViewFieldDTO city = field("city", "城市", DeTypeConstants.DE_STRING);
        ChartViewFieldDTO amount = field("amount", "销售额", DeTypeConstants.DE_FLOAT);
        amount.setFormatterCfg(new FormatterCfgDTO().setType("value").setDecimalCount(2));

        ChartViewDTO view = new ChartViewDTO();
        view.setType("table-info");
        view.setXAxis(new ArrayList<>(Arrays.asList(province, amount)));
        view.setDrillFields(new ArrayList<>(List.of(city)));
        Map<String, Object> customAttr = new HashMap<>();
        customAttr.put("tableHeader", new HashMap<String, Object>());
        customAttr.put("tableCell", new HashMap<>(Map.of("mergeCells", false)));
        view.setCustomAttr(customAttr);
        Map<String, Object> data = new HashMap<>();
        data.put("fields", new ArrayList<>(Arrays.asList(province, city, amount)));
        view.setData(data);

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("data");
            CellStyle headStyle = workbook.createCellStyle();
            List<Object[]> details = new ArrayList<>();
            details.add(new Object[]{"省份", "城市", "销售额"});
            details.add(new Object[]{"浙江", "杭州", "1234.5"});

            ChartDataServer.setExcelData(
                    sheet,
                    headStyle,
                    new Object[]{"省份", "城市", "销售额"},
                    details,
                    null,
                    new Integer[]{DeTypeConstants.DE_STRING, DeTypeConstants.DE_STRING, DeTypeConstants.DE_FLOAT},
                    view,
                    workbook
            );

            assertEquals("General", sheet.getRow(1).getCell(1).getCellStyle().getDataFormatString());
            assertEquals("0.00", sheet.getRow(1).getCell(2).getCellStyle().getDataFormatString());
            assertEquals(1234.5D, sheet.getRow(1).getCell(2).getNumericCellValue(), 0.0001D);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void transTableNormal_countLikeTextMeasureIsNumeric() {
        ChartViewFieldDTO dim = field("channel", "渠道", DeTypeConstants.DE_STRING);
        dim.setGroupType("d");
        dim.setFieldInstanceId("d1");
        ChartViewFieldDTO count = field("channel", "渠道", DeTypeConstants.DE_STRING);
        count.setGroupType("q");
        count.setSummary("count");
        count.setFieldInstanceId("q1");
        ChartViewFieldDTO distinct = field("channel", "渠道", DeTypeConstants.DE_STRING);
        distinct.setGroupType("q");
        distinct.setSummary("count_distinct");
        distinct.setFieldInstanceId("q2");
        ChartViewFieldDTO sum = field("amount", "金额", DeTypeConstants.DE_FLOAT);
        sum.setGroupType("q");
        sum.setSummary("sum");
        sum.setFieldInstanceId("q3");

        ChartViewDTO view = new ChartViewDTO();
        List<String[]> rows = Arrays.asList(new String[][]{{"抖音", "12", "8", "99.5"}});
        Map<String, Object> map = ChartDataBuild.transTableNormal(
                List.of(dim, count, distinct, sum),
                view,
                rows,
                Collections.emptyMap());
        Map<String, Object> row = ((List<Map<String, Object>>) map.get("tableRow")).get(0);

        assertEquals("抖音", row.get("fi_d1"));
        assertTrue(row.get("fi_q1") instanceof BigDecimal);
        assertEquals(new BigDecimal("12").setScale(8), row.get("fi_q1"));
        assertTrue(row.get("fi_q2") instanceof BigDecimal);
        assertTrue(row.get("fi_q3") instanceof BigDecimal);
    }

    @Test
    public void setExcelData_countLikeTextMeasureIsNumericCell() {
        ChartViewFieldDTO dim = field("channel", "渠道", DeTypeConstants.DE_STRING);
        dim.setGroupType("d");
        ChartViewFieldDTO count = field("channel", "计数", DeTypeConstants.DE_STRING);
        count.setGroupType("q");
        count.setSummary("count");
        count.setFormatterCfg(new FormatterCfgDTO().setType("value").setDecimalCount(0));

        ChartViewDTO view = new ChartViewDTO();
        view.setType("table-normal");
        view.setXAxis(new ArrayList<>(List.of(dim)));
        view.setYAxis(new ArrayList<>(List.of(count)));
        Map<String, Object> customAttr = new HashMap<>();
        customAttr.put("tableHeader", new HashMap<String, Object>());
        customAttr.put("tableCell", new HashMap<>(Map.of("mergeCells", false)));
        view.setCustomAttr(customAttr);
        Map<String, Object> data = new HashMap<>();
        data.put("fields", new ArrayList<>(Arrays.asList(dim, count)));
        view.setData(data);

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("data");
            ChartDataServer.setExcelData(
                    sheet,
                    workbook.createCellStyle(),
                    new Object[]{"渠道", "计数"},
                    new ArrayList<>(Arrays.asList(new Object[]{"渠道", "计数"}, new Object[]{"抖音", "132194"})),
                    null,
                    new Integer[]{DeTypeConstants.DE_STRING, DeTypeConstants.DE_STRING},
                    view,
                    workbook
            );
            assertEquals(CellType.STRING, sheet.getRow(1).getCell(0).getCellType());
            assertEquals(CellType.NUMERIC, sheet.getRow(1).getCell(1).getCellType());
            assertEquals(132194D, sheet.getRow(1).getCell(1).getNumericCellValue(), 0.0001D);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void filterExportHeaderUsesCurrentDrillFieldsInsteadOfStaleViewAxes() {
        ChartViewFieldDTO province = field("province", "省份", DeTypeConstants.DE_STRING);
        ChartViewFieldDTO amount = field("amount", "销售额", DeTypeConstants.DE_FLOAT);
        ChartViewFieldDTO city = field("city", "城市", DeTypeConstants.DE_STRING);

        ChartViewDTO view = new ChartViewDTO();
        view.setXAxis(new ArrayList<>(Arrays.asList(province, amount)));
        view.setDrillFields(new ArrayList<>(List.of(city)));

        Map<String, Object> data = new HashMap<>();
        data.put("fields", new ArrayList<>(Arrays.asList(province, city, amount)));
        view.setData(data);

        Object[] header = ChartDataServer.filterExportHeader(new Object[]{"省份", "城市", "销售额"}, view);

        assertEquals(Arrays.asList("省份", "城市", "销售额"), Arrays.asList(header));
    }

    private ChartDataServer chartDataServerWithExportLimits(Long viewLimit, Long datasetLimit) throws Exception {
        ChartDataManage chartDataManage = mock(ChartDataManage.class);
        when(chartDataManage.calcData(any(ChartViewDTO.class))).thenAnswer(invocation -> {
            ChartViewDTO result = new ChartViewDTO();
            Map<String, Object> data = new HashMap<>();
            data.put("sourceData", new ArrayList<Object[]>());
            data.put("fields", List.of(field("name", "名称", DeTypeConstants.DE_STRING)));
            result.setData(data);
            return result;
        });

        ExportCenterLimitManage limitManage = new ExportCenterLimitManage();
        ReflectionTestUtils.setField(limitManage, "viewLimit", viewLimit);
        ReflectionTestUtils.setField(limitManage, "datasetLimit", datasetLimit);
        new ExportCenterUtils().setExportCenterLimitManage(limitManage);

        ChartDataServer chartDataServer = new ChartDataServer();
        ReflectionTestUtils.setField(chartDataServer, "chartDataManage", chartDataManage);
        return chartDataServer;
    }

    private static ChartViewFieldDTO field(String dataeaseName, String name, Integer deType) {
        ChartViewFieldDTO field = new ChartViewFieldDTO();
        field.setDataeaseName(dataeaseName);
        field.setName(name);
        field.setDeType(deType);
        return field;
    }
}
