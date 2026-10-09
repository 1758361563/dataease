package io.dataease.extensions.view.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class SortAxis {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String name;
    /** Optional placement identity; old configurations contain only id/name. */
    private String axis;
    private String fieldInstanceId;
    /** Pivot row sort range. Missing on legacy charts means GROUP. */
    private String scope;
}
