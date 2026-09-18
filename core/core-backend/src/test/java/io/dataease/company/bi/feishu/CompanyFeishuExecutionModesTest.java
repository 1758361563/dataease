package io.dataease.company.bi.feishu;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CompanyFeishuExecutionModesTest {

    @Test
    void storedModeWinsOverScreenshotAndMessageId() {
        CompanyFeishuRepository.DeliveryTarget withMessage = new CompanyFeishuRepository.DeliveryTarget();
        withMessage.feishuMessageId = "om_1";
        assertEquals("DRY_RUN", CompanyFeishuExecutionModes.resolve("DRY_RUN", null, List.of(withMessage)));
        assertFalse(CompanyFeishuExecutionModes.canUpgrade("DRY_RUN", "LIVE"));
    }

    @Test
    void screenshotAndUploadAreNotLiveEvidence() {
        CompanyFeishuRepository.DeliveryTarget ok = new CompanyFeishuRepository.DeliveryTarget();
        ok.status = "SUCCEEDED";
        assertEquals("UNKNOWN", CompanyFeishuExecutionModes.resolve(null, null, List.of(ok)));
    }

    @Test
    void historicalUnknownStaysUnknown() {
        CompanyFeishuRepository.DeliveryTarget ok = new CompanyFeishuRepository.DeliveryTarget();
        ok.status = "SUCCEEDED";
        assertEquals("UNKNOWN", CompanyFeishuExecutionModes.resolve("UNKNOWN", null, List.of(ok)));
    }

    @Test
    void messageIdBackfillsLiveOnlyWhenUnstored() {
        CompanyFeishuRepository.DeliveryTarget sent = new CompanyFeishuRepository.DeliveryTarget();
        sent.feishuMessageId = "om_live";
        assertEquals("LIVE", CompanyFeishuExecutionModes.resolve(null, null, List.of(sent)));
    }

    @Test
    void mixedLiveTargetsKeepRunModeAndOwnStatus() {
        CompanyFeishuRepository.DeliveryTarget ok = new CompanyFeishuRepository.DeliveryTarget();
        ok.feishuMessageId = "om_ok";
        ok.status = "SUCCEEDED";
        CompanyFeishuRepository.DeliveryTarget failed = new CompanyFeishuRepository.DeliveryTarget();
        failed.status = "FAILED";
        failed.errorSummary = "unreachable";
        String runMode = CompanyFeishuExecutionModes.resolve("LIVE", "1/2 targets failed", List.of(ok, failed));
        assertEquals("LIVE", runMode);
        assertEquals("LIVE", CompanyFeishuExecutionModes.resolveTarget(runMode, null, null, ok.feishuMessageId));
        assertEquals("LIVE", CompanyFeishuExecutionModes.resolveTarget(runMode, null, failed.errorSummary, null));
    }
}
