package io.dataease.company.bi.integration;

import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.company.bi.auth.CompanyTokenSecret;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class IntegrationSecretCipher {
    private final byte[] key;

    public IntegrationSecretCipher(CompanyTokenSecret secret) {
        try {
            key = MessageDigest.getInstance("SHA-256").digest(
                    ("companybi/integration/v1\n" + secret.getSecret()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException("Cannot initialize integration encryption", e); }
    }

    // Passed only to the child process, never returned by an HTTP endpoint.
    public String workerKey() { return Base64.getEncoder().encodeToString(key); }

    public String encrypt(String provider, String connectionKey, String plain) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(provider, connectionKey));
            return "v1." + Base64.getEncoder().encodeToString(iv) + "."
                    + Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Cannot encrypt integration settings"); }
    }

    public String decrypt(String provider, String connectionKey, String encrypted) {
        try {
            String[] parts = encrypted.split("\\.");
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
            cipher.updateAAD(aad(provider, connectionKey));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("无法解密集成凭据，请检查部署密钥及备份"); }
    }

    private static byte[] aad(String provider, String key) {
        return (provider + ":" + key).getBytes(StandardCharsets.UTF_8);
    }
}
