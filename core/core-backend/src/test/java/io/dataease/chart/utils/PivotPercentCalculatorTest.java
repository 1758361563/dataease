package io.dataease.chart.utils;

import io.dataease.extensions.view.dto.*;
import java.util.*;

public class PivotPercentCalculatorTest {
    private static ChartViewFieldDTO field(String key, String summary) {
        var f = new ChartViewFieldDTO(); f.setId(1L); f.setDataeaseName(key); f.setName(key); f.setSummary(summary); return f;
    }
    private static void close(String value, double expected) {
        if (value == null || Math.abs(Double.parseDouble(value) - expected) > 1e-10) throw new AssertionError(value + " != " + expected);
    }
    public static void main(String[] args) {
        var channel = field("channel",null); var year = field("date",null); year.setFieldInstanceId("year");
        var category = field("category",null); var month = field("date",null); month.setFieldInstanceId("month");
        var sales = field("sales","sum"); var count = field("count","count");
        var calc = new ChartFieldCompareDTO(); calc.setType("percent"); calc.setPercentScope("rowGroup"); calc.setPercentGroup(year.resultKey());
        sales.setCompareCalc(calc); count.setCompareCalc(calc);
        var view = new ChartViewDTO(); view.setType("table-pivot"); view.setXAxis(List.of(channel,year,category)); view.setXAxisExt(List.of(month));
        var dims = List.of(channel,year,category,month); var measures = List.of(sales,count);
        var raw = new ArrayList<>(List.of(
            new String[]{"X","2026","A","01","80","8"},new String[]{"X","2026","B","01","20","2"},
            new String[]{"X","2026","A","02","20","2"},new String[]{"X","2026","B","02","180","18"},
            new String[]{"Y","2026","A","01","10","1"},new String[]{"Y","2026","B","01","30","3"},
            new String[]{"X","2025","A","01","5","1"},new String[]{"X","2025","B","01","5","1"}));
        PivotPercentCalculator.validate(view,dims,measures);
        if (!PivotPercentCalculator.required(view,measures)) throw new AssertionError("scope not detected");
        var output = raw.stream().map(String[]::clone).toList();
        var basis = PivotPercentCalculator.apply(view,dims,measures,raw,output);
        close(output.get(0)[4],.8);close(output.get(1)[4],.2);close(output.get(2)[4],.1);close(output.get(3)[4],.9);
        close(output.get(4)[4],.25);close(output.get(6)[4],.5);close(output.get(0)[5],.8);
        if (!raw.get(0)[4].equals("80") || !basis.get(0).get("sales").equals("80")) throw new AssertionError("raw basis modified");
        calc.setPercentScope("row"); output=raw.stream().map(String[]::clone).toList(); PivotPercentCalculator.apply(view,dims,measures,raw,output); close(output.get(0)[4],.8);close(output.get(2)[4],.2);
        calc.setPercentScope("column"); output=raw.stream().map(String[]::clone).toList(); PivotPercentCalculator.apply(view,dims,measures,raw,output);close(output.get(0)[4],80.0/150);
        calc.setPercentScope("rowGroup");calc.setPercentGroup(category.resultKey());
        try {PivotPercentCalculator.validate(view,dims,measures);throw new AssertionError("leaf accepted");}catch(IllegalArgumentException expected){}
        calc.setPercentGroup(year.resultKey()); sales.setSummary("avg");
        try {PivotPercentCalculator.validate(view,dims,measures);throw new AssertionError("average accepted");}catch(IllegalArgumentException expected){}
        sales.setSummary("sum"); var zeros=new ArrayList<>(List.<String[]>of(new String[]{"X","2026","A","01","0","0"})); output=zeros.stream().map(String[]::clone).toList();
        PivotPercentCalculator.apply(view,dims,measures,zeros,output);if(output.get(0)[4]!=null)throw new AssertionError("zero denominator");
        try {PivotPercentCalculator.validateMasked(dims,measures,Set.of("channel"));throw new AssertionError("masked raw basis exposed");}catch(IllegalArgumentException expected){}
        calc.setPercentScope("table");if(PivotPercentCalculator.required(view,measures))throw new AssertionError("legacy scope changed");
        System.out.println("Pivot percent backend: parent groups, row/column, two measures, placement identity, raw basis, validation and zero denominator passed.");
    }
}
