package io.dataease.company.bi.feishu;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

public final class CompanyFeishuExecutionModes {

    public static final String DRY_RUN = "DRY_RUN";
    public static final String LIVE = "LIVE";
    public static final String UNKNOWN = "UNKNOWN";

    private CompanyFeishuExecutionModes() {
    }

    public static String normalize(String storedMode) {
        if (StringUtils.isBlank(storedMode)) {
            return null;
        }
        String mode = storedMode.trim().toUpperCase();
        if (DRY_RUN.equals(mode) || LIVE.equals(mode) || UNKNOWN.equals(mode)) {
            return mode;
        }
        return null;
    }

    public static String resolve(String storedMode,
                                 String errorSummary,
                                 List<CompanyFeishuRepository.DeliveryTarget> targets) {
        String stored = normalize(storedMode);
        if (stored != null) {
            return stored;
        }
        if (containsDryRun(errorSummary) || anyTargetDryRun(targets)) {
            return DRY_RUN;
        }
        if (anyMessageId(targets)) {
            return LIVE;
        }
        return UNKNOWN;
    }

    public static String resolveTarget(String runMode,
                                       String storedMode,
                                       String errorSummary,
                                       String messageId) {
        String stored = normalize(storedMode);
        if (stored != null) {
            return stored;
        }
        if (containsDryRun(errorSummary) || DRY_RUN.equals(runMode)) {
            return DRY_RUN;
        }
        if (LIVE.equals(runMode) || StringUtils.isNotBlank(messageId)) {
            return LIVE;
        }
        return UNKNOWN;
    }

    public static boolean canUpgrade(String fromMode, String toMode) {
        return !(DRY_RUN.equals(normalize(fromMode)) && LIVE.equals(normalize(toMode)));
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
