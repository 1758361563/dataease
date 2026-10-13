package io.dataease.visualization.calculation;

import io.dataease.api.visualization.dto.DashboardCalculationConfig;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** One reference instant and timezone for all defaults in a query batch. */
public class DashboardParameterResolver {
    private static final Pattern FORMAT = Pattern.compile("\\{\\{\\{yesterday %(yyyy-MM-dd|yyyy-MM-01|yyyy-01-01)}}}");

    public Map<String, String> defaults(DashboardCalculationConfig config, Instant reference) {
        LocalDate today = reference.atZone(ZoneId.of(config.getTimezone())).toLocalDate();
        Map<String, String> values = new LinkedHashMap<>();
        for (var parameter : config.getParameters()) {
            var definition = parameter.getDefaultDefinition();
            if (definition == null) {
                values.put(parameter.getId().toString(), null);
            } else if ("FIXED".equals(definition.getMode())) {
                values.put(parameter.getId().toString(), LocalDate.parse(definition.getValue()).toString());
            } else if ("MACRO".equals(definition.getMode())) {
                values.put(parameter.getId().toString(), evaluate(definition.getExpression(), today).toString());
            } else if ("RELATIVE".equals(definition.getMode())) {
                values.put(parameter.getId().toString(), relative(definition.getRelative(), today).toString());
            } else {
                throw new IllegalArgumentException("不支持的默认值模式：" + parameter.getName());
            }
        }
        return values;
    }

    public LocalDate relative(DashboardCalculationConfig.RelativeDate rule, LocalDate today) {
        if (rule == null || rule.getOffset() == null || Math.abs((long) rule.getOffset()) > 1200)
            throw new IllegalArgumentException("相对日期偏移必须是 -1200 到 1200 的整数");
        LocalDate base = switch (rule.getBase() == null ? "" : rule.getBase()) {
            case "TODAY" -> today;
            case "YESTERDAY" -> today.minusDays(1);
            default -> throw new IllegalArgumentException("不支持的相对日期基准");
        };
        int offset = rule.getOffset();
        String period = rule.getPeriod() == null ? "" : rule.getPeriod();
        LocalDate start = switch (period) {
            case "DAY" -> base.plusDays(offset);
            case "WEEK" -> base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset);
            case "MONTH" -> base.withDayOfMonth(1).plusMonths(offset);
            case "QUARTER" -> base.withDayOfMonth(1).withMonth((base.getMonthValue() - 1) / 3 * 3 + 1).plusMonths(offset * 3L);
            case "YEAR" -> base.withDayOfYear(1).plusYears(offset);
            default -> throw new IllegalArgumentException("不支持的相对日期周期");
        };
        if ("START".equals(rule.getPosition())) return start;
        if (!"END".equals(rule.getPosition())) throw new IllegalArgumentException("不支持的相对日期位置");
        return switch (period) {
            case "DAY" -> start;
            case "WEEK" -> start.plusDays(6);
            case "MONTH" -> start.plusMonths(1).minusDays(1);
            case "QUARTER" -> start.plusMonths(3).minusDays(1);
            case "YEAR" -> start.plusYears(1).minusDays(1);
            default -> throw new IllegalArgumentException("不支持的相对日期周期");
        };
    }

    public LocalDate evaluate(String expression, LocalDate today) {
        if (expression == null) throw new IllegalArgumentException("时间宏不能为空");
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return switch (expression.trim()) {
            case "{{{monday last week}}}" -> monday.minusWeeks(1);
            case "{{{sunday last week}}}" -> monday.minusDays(1);
            case "{{{today}}}" -> today;
            case "{{{yesterday}}}" -> today.minusDays(1);
            case "{{{month beginning}}}" -> today.withDayOfMonth(1);
            default -> {
                var matcher = FORMAT.matcher(expression.trim());
                if (!matcher.matches()) throw new IllegalArgumentException("不支持的时间宏：" + expression);
                yield LocalDate.parse(today.minusDays(1).format(DateTimeFormatter.ofPattern(matcher.group(1))));
            }
        };
    }
}
