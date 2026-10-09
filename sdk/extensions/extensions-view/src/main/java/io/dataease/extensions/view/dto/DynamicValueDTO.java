package io.dataease.extensions.view.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;

/**
 * @Author Junjun
 */
@Data
public class DynamicValueDTO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fieldId;
    // Preserve the placement identity alongside the legacy physical field id.
    private String fieldInstanceId;
    private String metricInstanceId;
    private String summary;
    private BigDecimal value;
    private String stringValue;
}
