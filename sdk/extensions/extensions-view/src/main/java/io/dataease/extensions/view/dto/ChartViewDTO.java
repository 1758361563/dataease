package io.dataease.extensions.view.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @Author gin
 */
@Data
public class ChartViewDTO extends ChartViewBaseDTO {
    private Map<String, Object> data;
    private String privileges;
    private Boolean isLeaf;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long pid;
    private String sql;
    private boolean drill;
    private List<ChartExtFilterDTO> drillFilters;
    private String position;

    private long totalPage;
    private long totalItems;
    private int datasetMode;
    private String datasourceType;

    private ChartExtRequest chartExtRequest;
    private Boolean isExcelExport = false;
    private Boolean exportDatasetOriginData = false;
    private boolean cache;
    /**
     * 原始数据集表ID
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceTableId;

    // 数据下载模式 dataset 指的是现在原始数据
    private String downloadType;

    /** Matches visualization source: core (published) or snapshot (editor). */
    private String resourceTable = "core";

    /** Editor definitions and per-query values are transient, never stored in core_chart_view. */
    private String calculationConfig;
    private java.util.Map<String, String> dashboardParameterValues;
    private String dashboardParameterReference;
}
