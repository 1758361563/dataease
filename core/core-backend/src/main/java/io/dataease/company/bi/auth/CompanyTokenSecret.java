package io.dataease.company.bi.auth;

import io.dataease.utils.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component("companyTokenSecret")
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyTokenSecret {

    private static final String SECRET_FILE = "company-bi-token.secret";

    private final String secret;

    public CompanyTokenSecret(
            @Value("${company.bi.token-secret:}") String configuredSecret,
            @Value("${company.bi.data-dir:/opt/dataease2.0/data}") String dataDir) {
        this.secret = resolveSecret(configuredSecret, dataDir);
    }

    public String getSecret() {
        return secret;
    }

    private static String resolveSecret(String configuredSecret, String dataDir) {
        if (StringUtils.isNotBlank(configuredSecret)) {
            return configuredSecret.trim();
        }
        Path file = Path.of(dataDir, SECRET_FILE);
        try {
            if (Files.exists(file)) {
                String stored = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (StringUtils.isNotBlank(stored)) {
                    return stored;
                }
            }
            String generated = UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            Files.createDirectories(file.getParent());
            Files.writeString(file, generated, StandardCharsets.UTF_8);
            LogUtil.info("Generated company token HMAC secret at " + file + ". Tokens persist across restarts of this instance.");
            return generated;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist company token secret at " + file, e);
        }
    }
}
