package io.dataease.exportCenter.manage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatasetPreviewExportWriterTest {
    @Test void csvQuotesCommasQuotesNewlinesAndChinese() {
        assertEquals("\"气味,\"\"图书馆\"\"\n第二行\"", DatasetPreviewExportWriter.csvCell("气味,\"图书馆\"\n第二行"));
        assertEquals("\"\"", DatasetPreviewExportWriter.csvCell(null));
    }
    @Test void csvNeutralizesFormulasAndPreservesNegativeNumbers() {
        assertEquals("\"'=1+1\"", DatasetPreviewExportWriter.csvCell("=1+1"));
        assertEquals("\"'  @SUM(A1)\"", DatasetPreviewExportWriter.csvCell("  @SUM(A1)"));
        assertEquals("\"-12.5\"", DatasetPreviewExportWriter.csvCell("-12.5"));
    }
}
