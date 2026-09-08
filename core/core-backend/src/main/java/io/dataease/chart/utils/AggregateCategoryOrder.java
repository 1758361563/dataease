package io.dataease.chart.utils;

import java.math.BigDecimal;
import java.util.*;

/** Orders complete grouped results before applying a category limit. */
public final class AggregateCategoryOrder {
    private AggregateCategoryOrder() {}

    public static <K> List<K> order(Collection<K> categories, Map<K, BigDecimal> values,
                                    boolean ascending, Comparator<K> tieBreaker, int limit) {
        Comparator<BigDecimal> numbers = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
        Comparator<K> comparator = Comparator.comparing(values::get, Comparator.nullsLast(numbers));
        return categories.stream().distinct().sorted(comparator.thenComparing(tieBreaker))
                .limit(Math.max(0, limit)).toList();
    }
}
