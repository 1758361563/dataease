package io.dataease.company.bi.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Map;

@Repository
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class IntegrationConnectionStore {
    public record Connection(Map<String, Object> config, Map<String, String> secrets) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final IntegrationSecretCipher cipher;
    public IntegrationConnectionStore(JdbcTemplate jdbc, IntegrationSecretCipher cipher) {
        this.jdbc = jdbc; this.cipher = cipher;
    }
    public Connection find(String provider, String key) {
        var rows = jdbc.query("SELECT config_json,secret_cipher FROM company_integration_connection WHERE provider=? AND connection_key=?",
                (rs, n) -> {
                    try {
                        return new Connection(JSON.readValue(rs.getString(1), Map.class),
                                JSON.readValue(cipher.decrypt(provider, key, rs.getString(2)), Map.class));
                    } catch (Exception e) { throw new IllegalStateException("无法读取集成连接配置"); }
                }, provider, key);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public void save(String provider, String key, Map<String, Object> config, Map<String, String> secrets, long operator) {
        try {
            jdbc.update("""
                    INSERT INTO company_integration_connection(provider,connection_key,config_json,secret_cipher,updated_at,updated_by)
                    VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE config_json=VALUES(config_json),
                    secret_cipher=VALUES(secret_cipher),updated_at=VALUES(updated_at),updated_by=VALUES(updated_by)
                    """, provider, key, JSON.writeValueAsString(config),
                    cipher.encrypt(provider, key, JSON.writeValueAsString(secrets)), System.currentTimeMillis(), operator);
        } catch (Exception e) { throw new IllegalStateException("无法保存集成连接配置"); }
    }
}
