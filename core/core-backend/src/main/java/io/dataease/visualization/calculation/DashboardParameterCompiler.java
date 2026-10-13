package io.dataease.visualization.calculation;

import io.dataease.api.visualization.dto.DashboardCalculationConfig;
import io.dataease.extensions.view.dto.ChartViewFieldDTO;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Bind validated DATE literals before the existing field expansion and aggregation pipeline. */
public class DashboardParameterCompiler {
    private static final Pattern PARAMETER = Pattern.compile("\\[(DVP\\.[^\\]]+|DYNAMIC_PARAMS\\.[^\\]]+)]");

    public void bind(DashboardCalculationConfig config, Map<String, String> supplied, Instant reference,
                     List<ChartViewFieldDTO> fields) {
        Map<String, String> values = new DashboardParameterResolver().defaults(config, reference);
        if (supplied != null) {
            for (String id : supplied.keySet()) {
                if (!values.containsKey(id)) throw new IllegalArgumentException("参数不属于当前仪表板：" + id);
            }
            values.putAll(supplied);
        }
        Map<String, String> literals = new HashMap<>();
        for (var parameter : config.getParameters()) {
            String value = values.get(parameter.getId().toString());
            String literal;
            if (value == null || value.isBlank()) {
                if (parameter.isRequired()) throw new IllegalArgumentException("请设置日期参数：" + parameter.getName());
                literal = "CAST(NULL AS DATE)";
            } else {
                LocalDate date = LocalDate.parse(value);
                if (!date.toString().equals(value) || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                    throw new IllegalArgumentException("日期格式须为yyyy-MM-dd：" + parameter.getName());
                literal = "CAST('" + date + "' AS DATE)";
            }
            literals.put("DVP." + parameter.getId(), literal);
            literals.put("DYNAMIC_PARAMS." + parameter.getName(), literal);
        }
        for (var field : fields) {
            if (field.getOriginName() == null) continue;
            String expression = field.getOriginName();
            Matcher matcher = PARAMETER.matcher(expression);
            StringBuilder bound = new StringBuilder();
            while (matcher.find()) {
                if (DashboardCalculationValidator.insideLiteral(expression, matcher.start())) continue;
                String literal = literals.get(matcher.group(1));
                if (literal == null) throw new IllegalArgumentException("参数引用不存在：" + matcher.group(1));
                matcher.appendReplacement(bound, Matcher.quoteReplacement(literal));
            }
            matcher.appendTail(bound);
            String sql = bound.toString();
            if ("DASHBOARD".equals(field.getFieldScope()) || PARAMETER.matcher(expression).find()) {
                sql = DashboardFormulaSyntax.sql(sql);
            }
            field.setOriginName(sql);
        }
    }
}
