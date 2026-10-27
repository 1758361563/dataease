package io.dataease.company.bi.integration;

import io.dataease.company.bi.auth.CompanyTokenSecret;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationSecretCipherTest {
    private final IntegrationSecretCipher cipher = new IntegrationSecretCipher(new CompanyTokenSecret("unit-test-master", "unused"));
    @Test void roundTripsWithoutPersistingPlaintextAndUsesFreshIv() {
        String encrypted = cipher.encrypt("FEISHU", "default", "{\"appSecret\":\"test-only\"}");
        assertFalse(encrypted.contains("test-only"));
        assertEquals("{\"appSecret\":\"test-only\"}", cipher.decrypt("FEISHU", "default", encrypted));
        assertNotEquals(encrypted, cipher.encrypt("FEISHU", "default", "{\"appSecret\":\"test-only\"}"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("OTHER", "default", encrypted));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("FEISHU", "other", encrypted));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("FEISHU", "default", encrypted.substring(0, encrypted.length() - 4)));
    }
    @Test void readsNodeGcmFixture() {
        assertEquals("{\"appSecret\":\"test-only\"}", cipher.decrypt("FEISHU", "default",
                "v1.BwcHBwcHBwcHBwcH.KzjDT5vnK258E+wnnjt+MKZl8mcOrMyGr42JE9Sq32J58IChT8bCUdU="));
    }
}
