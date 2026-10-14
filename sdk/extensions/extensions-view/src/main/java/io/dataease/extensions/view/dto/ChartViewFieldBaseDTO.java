package io.dataease.extensions.view.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import lombok.Data;

import java.io.Serializable;

/**
 * 图表字段base类，与数据集字段表基本一致
 */
@Data
public class ChartViewFieldBaseDTO extends DatasetTableFieldDTO implements Serializable {

    private String summary;

    private String sort;

    /**
     * 日期解析格式，后端参与聚合运算
     */
    private String dateStyle;

    /**
     * 日期分隔符
     */
    private String datePattern;

    /**
     * 日期显示格式，仅前端图表格式化
     */
    private String dateShowFormat;

    private Integer extField;

    private String chartType;

    private ChartFieldCompareDTO compareCalc;

    private String logic;

    private String filterType;

    private Long chartId;

    private Integer index;

    private FormatterCfgDTO formatterCfg;

    private String chartShowName;

    /** Pivot measure placement: all detail/total columns, or totals only. */
    private String pivotDisplayScope = "all";

    /**
     * 图表 placement 身份。同一 Dataset Field 在轴上多次出现时各有独立实例。
     * 不进 Dataset 表；由前后端 JSON round-trip。
     */
    private String fieldInstanceId;

    /**
     * 对比指标卡既有 placement 身份。resultKey 派生时作为 fieldInstanceId 的 fallback。
     */
    private String metricInstanceId;

    /**
     * 查询结果列身份。不持久化，由 fieldInstanceId / metricInstanceId / dataeaseName 派生。
     */
    @JsonIgnore
    public String resultKey() {
        if (fieldInstanceId != null && !fieldInstanceId.isEmpty()) {
            return "fi_" + fieldInstanceId;
        }
        if (metricInstanceId != null && !metricInstanceId.isEmpty()) {
            return "mi_" + metricInstanceId;
        }
        return getDataeaseName();
    }

    /**
     * seriesSummary.field 等配置既可能存 resultKey，也可能仍是旧的 dataeaseName。
     */
    @JsonIgnore
    public boolean matchesResultIdentity(String key) {
        if (key == null) {
            return false;
        }
        return key.equals(resultKey()) || key.equals(getDataeaseName());
    }

}
