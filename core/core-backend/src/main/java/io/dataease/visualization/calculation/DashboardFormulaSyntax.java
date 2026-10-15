package io.dataease.visualization.calculation;

import java.util.*;

/** Translate dashboard formula functions before the existing SQL dialect pipeline. */
final class DashboardFormulaSyntax {
    static String sql(String expression) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < expression.length();) {
            char c = expression.charAt(i);
            if (DashboardCalculationValidator.insideLiteral(expression, i) || !(Character.isLetter(c) || c == '_')) {
                result.append(c); i++; continue;
            }
            int end = i + 1;
            while (end < expression.length() && (Character.isLetterOrDigit(expression.charAt(end)) || expression.charAt(end) == '_')) end++;
            String name = expression.substring(i, end);
            int open = end;
            while (open < expression.length() && Character.isWhitespace(expression.charAt(open))) open++;
            if (open >= expression.length() || expression.charAt(open) != '(') {
                result.append(expression, i, end); i = end; continue;
            }
            int close = closing(expression, open);
            List<String> arguments = split(expression.substring(open + 1, close));
            List<String> transformed = arguments.stream().map(DashboardFormulaSyntax::sql).toList();
            switch (name.toUpperCase(Locale.ROOT)) {
                case "IF" -> {
                    if (transformed.size() != 3) throw new IllegalArgumentException("if需要三个参数");
                    result.append("(CASE WHEN ").append(transformed.get(0)).append(" THEN ").append(transformed.get(1))
                            .append(" ELSE ").append(transformed.get(2)).append(" END)");
                }
                case "AND", "OR" -> {
                    if (transformed.size() < 2) throw new IllegalArgumentException(name + "需要至少两个参数");
                    result.append('(').append(String.join(" " + name.toUpperCase(Locale.ROOT) + " ", transformed)).append(')');
                }
                case "NOT" -> {
                    if (transformed.size() != 1) throw new IllegalArgumentException("not需要一个参数");
                    result.append("(NOT ").append(transformed.get(0)).append(')');
                }
                case "DATE_SUB", "ADD_MONTHS" -> {
                    if (transformed.size() != 2) throw new IllegalArgumentException(name + "需要两个参数");
                    // Preserve native SQL intervals; the two-argument formula form uses a day count.
                    if (name.equalsIgnoreCase("DATE_SUB") && transformed.get(1).stripLeading()
                            .matches("(?is)INTERVAL\\b.*")) {
                        result.append(name).append('(').append(String.join(",", transformed)).append(')');
                    } else {
                        boolean days = name.equalsIgnoreCase("DATE_SUB");
                        result.append("TIMESTAMPADD(").append(days ? "DAY,-(" : "MONTH,(")
                                .append(transformed.get(1)).append("),").append(transformed.get(0)).append(')');
                    }
                }
                default -> result.append(name).append('(').append(String.join(",", transformed)).append(')');
            }
            i = close + 1;
        }
        return result.toString();
    }

    private static int closing(String expression, int open) {
        int depth = 0;
        for (int i = open; i < expression.length(); i++) {
            if (DashboardCalculationValidator.insideLiteral(expression, i)) continue;
            if (expression.charAt(i) == '(') depth++;
            if (expression.charAt(i) == ')' && --depth == 0) return i;
        }
        throw new IllegalArgumentException("计算公式括号不匹配");
    }

    private static List<String> split(String expression) {
        List<String> result = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < expression.length(); i++) {
            if (DashboardCalculationValidator.insideLiteral(expression, i)) continue;
            char c = expression.charAt(i);
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) { result.add(expression.substring(start, i)); start = i + 1; }
        }
        result.add(expression.substring(start));
        return result;
    }
}
