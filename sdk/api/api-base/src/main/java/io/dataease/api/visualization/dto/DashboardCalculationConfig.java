package io.dataease.api.visualization.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Definitions belong to a visualization revision; current query values do not. */
@Data
public class DashboardCalculationConfig {
    private int version = 1;
    private String timezone = "Asia/Shanghai";
    private List<Parameter> parameters = new ArrayList<>();
    private List<SharedField> datasetFields = new ArrayList<>();

    @Data
    public static class Parameter {
        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;
        private String name;
        private String type = "DATE";
        private boolean required = true;
        @JsonProperty("default")
        private DefaultDefinition defaultDefinition;
    }

    @Data
    public static class DefaultDefinition {
        private String mode;
        private String value;
        private String expression;
        private RelativeDate relative;
    }

    @Data
    public static class RelativeDate {
        private String base = "TODAY";
        private String period = "MONTH";
        private Integer offset = 0;
        private String position = "START";
    }

    @Data
    public static class SharedField {
        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long datasetGroupId;
        private String name;
        private String groupType = "d";
        private Integer deType = 2;
        /** Field references: [fieldId]; parameter references: [DVP.parameterId]. */
        private String expression;
    }
}
