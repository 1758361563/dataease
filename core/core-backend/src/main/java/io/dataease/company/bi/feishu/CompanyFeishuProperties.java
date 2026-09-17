package io.dataease.company.bi.feishu;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "company.feishu")
public class CompanyFeishuProperties {

    private String shotBaseUrl = "http://127.0.0.1:8091";
    private String internalToken = "";
    private boolean dryRun = false;
    private int connectTimeoutMs = 15000;

    public String getShotBaseUrl() {
        return shotBaseUrl;
    }

    public void setShotBaseUrl(String shotBaseUrl) {
        this.shotBaseUrl = shotBaseUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }
}
