package io.dataease.api.dataset.dto;

import lombok.Data;

/**
 * @Author Junjun
 */
@Data
public class DataSetExportRequest extends DatasetNodeDTO {
    private String filename;
    private String expressionTree;
    private boolean dataEaseBi;
    private String format;
    private String previewJson;

    public String fileExtension() {
        if (format == null || "xlsx".equals(format)) return ".xlsx";
        if ("csv".equals(format)) return ".csv";
        throw new IllegalArgumentException("不支持的导出格式");
    }
}
