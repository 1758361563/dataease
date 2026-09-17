package io.dataease.company.bi.feishu;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

public final class CompanyFeishuExecutionModes {

    public static final String DRY_RUN = "DRY_RUN";
    public static final String LIVE = "LIVE";
    public static final String UNKNOWN = "UNKNOWN";

    private CompanyFeishuExecutionModes() {
    }

    public static String infer(String errorSummary,
                               String screenshotStatus,
                               String uploadStatus,
                               List<CompanyFeishuRepository.DeliveryTarget> targets) {
        if (containsDryRun(errorSummary) || anyTargetDryRun(targets)) {
            return DRY_RUN;
        }
        if (anyMessageId(targets)) {
            return LIVE;
        }
        if ("SUCCEEDED".equals(screenshotStatus) && "SUCCEEDED".equals(uploadStatus)) {
            return LIVE;
        }
        return UNKNOWN;
    }

    public static String inferTarget(String runMode,
                                     String errorSummary,
                                     String messageId) {
        if (containsDryRun(errorSummary) || DRY_RUN.equals(runMode)) {
            return DRY_RUN;
        }
        if (StringUtils.isNotBlank(messageId) || LIVE.equals(runMode)) {
            return LIVE;
        }
        return UNKNOWN;
    }

    private static boolean containsDryRun(String text) {
        return StringUtils.containsIgnoreCase(text, "dry-run");
    }

    private static boolean anyTargetDryRun(List<CompanyFeishuRepository.DeliveryTarget> targets) {
        if (targets == null) {
            return false;
        }
        for (CompanyFeishuRepository.DeliveryTarget target : targets) {
            if (containsDryRun(target.errorSummary)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyMessageId(List<CompanyFeishuRepository.DeliveryTarget> targets) {
        if (targets == null) {
            return false;
        }
        for (CompanyFeishuRepository.DeliveryTarget target : targets) {
            if (StringUtils.isNotBlank(target.feishuMessageId)) {
                return true;
            }
        }
        return false;
    }
}
