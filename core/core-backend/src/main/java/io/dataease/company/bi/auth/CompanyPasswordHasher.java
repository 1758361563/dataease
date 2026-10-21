package io.dataease.company.bi.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

@Component
public class CompanyPasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private static final String LONG_PASSWORD_PREFIX = "{pbkdf2-sha256}";
    private final Pbkdf2PasswordEncoder longPasswordEncoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public String hash(String rawPassword) {
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            return LONG_PASSWORD_PREFIX + longPasswordEncoder.encode(rawPassword);
        }
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String passwordHash) {
        if (rawPassword == null || passwordHash == null) {
            return false;
        }
        if (passwordHash.startsWith(LONG_PASSWORD_PREFIX)) {
            return longPasswordEncoder.matches(rawPassword, passwordHash.substring(LONG_PASSWORD_PREFIX.length()));
        }
        // Preserve existing BCrypt hashes without truncating long input or leaking an encoder exception.
        return rawPassword.getBytes(StandardCharsets.UTF_8).length <= 72 && encoder.matches(rawPassword, passwordHash);
    }
}
