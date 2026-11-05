package io.dataease.chart.utils;

import io.dataease.chart.charts.impl.table.TablePivotHandler;
import io.dataease.extensions.view.dto.ChartViewDTO;
import io.dataease.extensions.view.dto.ChartViewFieldDTO;
import io.dataease.extensions.view.dto.TableCalcTotalCfg;
import io.dataease.utils.IDUtils;
import io.dataease.utils.SnowFlake;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class PivotCustomTotalsTest {
    private final TablePivotHandler handler = new TablePivotHandler();
    private static SnowFlake previousIds;

    @BeforeAll static void initializeIdsWithoutSpring() throws Exception {
        var field = IDUtils.class.getDeclaredField("snowFlake");
        field.setAccessible(true);
        previousIds = (SnowFlake) field.get(null);
        new IDUtils().setSnowFlake(new SnowFlake(1, 1));
    }

    @AfterAll static void restoreIds() {
        new IDUtils().setSnowFlake(previousIds);
    }

    private ChartViewFieldDTO quota(String instance) {
        var field = new ChartViewFieldDTO();
        field.setDataeaseName("ratio");
        field.setFieldInstanceId(instance);
        return field;
    }

    private TableCalcTotalCfg config(String key, String aggregation, String expression) {
        var cfg = new TableCalcTotalCfg();
        cfg.setDataeaseName(key);
        cfg.setAggregation(aggregation);
        cfg.setOriginName(expression);
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private List<ChartViewFieldDTO> custom(List<ChartViewFieldDTO> quotas, TableCalcTotalCfg... cfg) throws Exception {
        var view = new ChartViewDTO();
        view.setYAxis(quotas);
        var method = TablePivotHandler.class.getDeclaredMethod("getCustomFields", ChartViewDTO.class, List.class);
        method.setAccessible(true);
        return (List<ChartViewFieldDTO>) method.invoke(handler, view, List.of(cfg));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> result(List<ChartViewFieldDTO> dimensions,
                                       List<ChartViewFieldDTO> fields, String[] row) throws Exception {
        var method = TablePivotHandler.class.getDeclaredMethod("buildCustomCalcResult", List.class, List.class, List.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(handler, List.<String[]>of(row), dimensions, fields);
    }

    @Test void legacyFormulaReturnsFourOverNineUnderTheActualMetricInstance() throws Exception {
        var cfg = config("ratio", "CUSTOM", "SUM([new])/SUM([orders])");
        var fields = custom(List.of(quota("card_ratio")), cfg);
        assertEquals(1, fields.size());
        assertEquals("fi_card_ratio", fields.getFirst().resultKey());
        assertEquals("ratio", fields.getFirst().getDataeaseName());
        assertEquals(cfg.getOriginName(), fields.getFirst().getOriginName());
        var category = new ChartViewFieldDTO();
        category.setDataeaseName("category");
        var totals = result(List.of(category), fields, new String[]{"incense", "0.44444444"});
        var categoryTotal = (Map<?, ?>) totals.get("incense");
        assertEquals(4.0 / 9.0, Double.parseDouble((String) categoryTotal.get("fi_card_ratio")), 1e-8);
        assertFalse(categoryTotal.containsKey("ratio"));
    }

    @Test void exactInstanceSettingsBeatLegacySettingsRegardlessOfOrder() throws Exception {
        var fields = custom(List.of(quota("first"), quota("second")),
                config("ratio", "CUSTOM", "legacy"), config("fi_first", "CUSTOM", "first-only"),
                config("fi_second", "SUM", null));
        assertEquals(1, fields.size());
        assertEquals("fi_first", fields.getFirst().resultKey());
        assertEquals("first-only", fields.getFirst().getOriginName());
    }

    @Test void repeatedPlacementsKeepIndependentCustomExpressions() throws Exception {
        var fields = custom(List.of(quota("first"), quota("second")),
                config("fi_first", "CUSTOM", "SUM([a])/SUM([b])"),
                config("fi_second", "CUSTOM", "SUM([c])/SUM([d])"));
        assertEquals(List.of("fi_first", "fi_second"), fields.stream().map(ChartViewFieldDTO::resultKey).toList());
        assertNotEquals(fields.get(0).getOriginName(), fields.get(1).getOriginName());
        assertNotEquals(fields.get(0).getId(), fields.get(1).getId());
        var totals = result(List.of(), fields, new String[]{"0.4", "0.8"});
        assertEquals(Map.of("fi_first", "0.4", "fi_second", "0.8"), totals);
    }

    @Test void legacyGraphsAndMetricIdentityFallbackRemainReadable() throws Exception {
        var legacy = custom(List.of(quota(null)), config("ratio", "CUSTOM", "legacy"));
        assertEquals("ratio", legacy.getFirst().resultKey());
        assertEquals(Map.of("ratio", "0.5"), result(List.of(), legacy, new String[]{"0.5"}));
        var metric = quota(null);
        metric.setMetricInstanceId("metric");
        assertEquals("mi_metric", custom(List.of(metric), config("ratio", "CUSTOM", "x")).getFirst().resultKey());
    }

    @Test void unknownAndBuiltInSettingsDoNotBecomeCustomQueries() throws Exception {
        assertTrue(custom(List.of(quota("first")), config("missing", "CUSTOM", "x")).isEmpty());
        assertTrue(custom(List.of(quota("first")), config("ratio", "AVG", null)).isEmpty());
        assertTrue(custom(List.of(quota("first"))).isEmpty());
    }

    public static void main(String[] args) {
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass(PivotCustomTotalsTest.class)).build());
        var writer = new java.io.PrintWriter(System.out);
        listener.getSummary().printTo(writer);
        listener.getSummary().printFailuresTo(writer);
        if (listener.getSummary().getTotalFailureCount() > 0 || listener.getSummary().getTestsFoundCount() == 0) System.exit(1);
    }
}
