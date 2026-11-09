package io.dataease.chart.charts.impl.others;

import java.util.*;
public class ParetoHandlerTest {
    public static void main(String[] args) {
        var input = List.of(new String[]{"b","20"},new String[]{"c","60"},new String[]{"a","20"},new String[]{"zero",null});
        var result = ParetoHandler.ordered(input,1);
        if (!result.stream().map(row->row[0]).toList().equals(List.of("c","a","b","zero"))) throw new AssertionError("order");
        if (!input.getFirst()[0].equals("b")) throw new AssertionError("input mutation");
        rejects(Collections.singletonList(new String[]{"negative","-1"}));
        rejects(Collections.singletonList(new String[]{"invalid","NaN"}));
        rejects(Collections.nCopies(5001,new String[]{"x","1"}));
        if (ParetoHandler.ordered(Collections.nCopies(5000,new String[]{"x","0"}),1).size()!=5000) throw new AssertionError("boundary");
        System.out.println("PASS Pareto descending/tie ordering, nulls, negatives, invalid values and category limit");
    }
    private static void rejects(List<String[]> data) {
        try { ParetoHandler.ordered(data,1); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid data accepted");
    }
}
