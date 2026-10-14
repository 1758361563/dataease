package io.dataease.extensions.view.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class ChartFieldCompareDTO {
    private String type = "none";
    private String resultData = "percent";
    private String percentScope = "table";
    /** Placement resultKey of the selected row parent, never the dataset id. */
    private String percentGroup;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long field;
    private ChartFieldCompareCustomDTO custom;
}
