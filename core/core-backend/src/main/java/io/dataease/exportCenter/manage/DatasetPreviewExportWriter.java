package io.dataease.exportCenter.manage;

import io.dataease.api.dataset.dto.DataSetExportRequest;
import io.dataease.dataset.manage.DatasetPreviewManage;
import io.dataease.dataset.manage.DatasetPreviewRequest;
import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import io.dataease.exportCenter.util.ExportCenterUtils;
import io.dataease.utils.JsonUtil;
import jakarta.annotation.Resource;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Writes both formats from the same immutable preview definition and query path. */
@Component
public class DatasetPreviewExportWriter {
    @Resource private DatasetPreviewManage preview;

    public void write(DataSetExportRequest export, OutputStream output, Consumer<String> progress, Consumer<Workbook> decorate) throws Exception {
        DatasetPreviewRequest request = JsonUtil.parseObject(export.getPreviewJson(), DatasetPreviewRequest.class);
        long total = ((Number) preview.exportPage(request, 0, 1, true).get("total")).longValue();
        long limit = ExportCenterUtils.getExportLimit("dataset");
        if (total > limit) throw new IllegalArgumentException("匹配数据共" + total + "行，超过导出上限" + limit + "行，请缩小筛选范围");
        Map<String, Object> firstPage = preview.exportPage(request, 0, (int) Math.min(1000L, Math.max(1L, total)), false);
        Map<String, Object> firstData = (Map<String, Object>) firstPage.get("data");
        List<DatasetTableFieldDTO> fields = (List<DatasetTableFieldDTO>) firstData.get("fields");
        boolean csv = ".csv".equals(export.fileExtension());
        SXSSFWorkbook workbook = csv ? null : new SXSSFWorkbook(100);
        Writer writer = csv ? new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8)) : null;
        if (writer != null) writer.write('\ufeff');
        try {
            Sheet sheet = csv ? null : workbook.createSheet("数据1");
            int rowNumber = 0;
            writeRow(writer, sheet, rowNumber++, fields.stream().map(DatasetTableFieldDTO::getName).toList(), null);
            for (int start = 0; start < total; start += 1000) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("导出已取消");
                Map<String, Object> page = start == 0 ? firstPage : preview.exportPage(request, start, (int) Math.min(1000L, total - start), false);
                Map<String, Object> pageData = (Map<String, Object>) page.get("data");
                List<Map<String, Object>> rows = (List<Map<String, Object>>) pageData.get("data");
                for (Map<String, Object> row : rows) {
                    if (!csv && rowNumber >= 1000000) {
                        sheet = workbook.createSheet("数据" + (workbook.getNumberOfSheets() + 1));
                        rowNumber = 0;
                        writeRow(null, sheet, rowNumber++, fields.stream().map(DatasetTableFieldDTO::getName).toList(), null);
                    }
                    List<String> values = new ArrayList<>();
                    for (DatasetTableFieldDTO field : fields) {
                        Object value = row.get(field.getDataeaseName());
                        values.add(value == null ? "" : String.valueOf(value));
                    }
                    writeRow(writer, sheet, rowNumber++, values, fields);
                }
                progress.accept(String.valueOf(Math.min(99, (start + rows.size()) * 100L / Math.max(total, 1))));
            }
            if (csv) writer.flush();
            else { decorate.accept(workbook); workbook.write(output); }
        } finally {
            if (workbook != null) { workbook.close(); workbook.dispose(); }
        }
    }

    static String csvCell(String value) {
        if (value == null) value = "";
        if (value.matches("(?s)^\\s*[=+@-].*") && !value.matches("-?\\d+(\\.\\d+)?")) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void writeRow(Writer writer, Sheet sheet, int rowIndex, List<String> values, List<DatasetTableFieldDTO> fields) throws IOException {
        if (writer != null) {
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) writer.write(',');
                writer.write(csvCell(values.get(i)));
            }
            writer.write("\r\n");
            return;
        }
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.size(); i++) {
            Cell cell = row.createCell(i);
            String value = values.get(i);
            boolean numeric = fields != null && Set.of(2, 3, 4).contains(fields.get(i).getDeType());
            if (numeric && value != null && value.matches("-?\\d{1,15}(\\.\\d+)?") && !value.matches("-?0\\d+.*") && new java.math.BigDecimal(value).precision() <= 15) {
                cell.setCellValue(Double.parseDouble(value));
            } else {
                cell.setCellValue(value);
            }
            if (fields == null) sheet.setColumnWidth(i, 24 * 256);
        }
    }
}
