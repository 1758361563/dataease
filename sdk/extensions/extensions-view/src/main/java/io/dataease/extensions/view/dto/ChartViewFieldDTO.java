package io.dataease.extensions.view.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 维度、指标、过滤器
 */
@Data
public class ChartViewFieldDTO extends ChartViewFieldBaseDTO implements Serializable {
    /**
     * 过滤
     */
    private List<ChartViewFieldFilterDTO> filter;

    /**
     * 排序
     */
    private List<String> customSort;

    private String busiType;

    private boolean isAgg;

    private boolean hide;

    /**
     * Actual Placement Role。d:Dimension，q:Measure。
     * 这一次图表 placement 实际扮演的角色，与 Dataset Default Role 解耦。
     * Dataset 默认角色见 DatasetTableFieldDTO.groupType。
     */
    private String groupType;

    /**
     * 字段来源
     */
    @JsonIgnore
    private FieldSource source;

    /**
     * 显隐
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Boolean show;
    private String field;
}
