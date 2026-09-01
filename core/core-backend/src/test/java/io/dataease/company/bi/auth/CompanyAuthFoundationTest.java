package io.dataease.company.bi.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.dataease.auth.vo.TokenVO;
import io.dataease.company.bi.user.CompanyAccountRole;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.company.bi.user.CompanyUserStatus;
import io.dataease.exception.DEException;
import io.dataease.result.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyAuthFoundationTest {

    private CompanyPasswordHasher hasher;
    private CompanyTokenIssuer issuer;
    private FakeUserService userService;

    @BeforeEach
    void setUp() throws Exception {
        hasher = new CompanyPasswordHasher();
        Path tempDir = Files.createTempDirectory("company-auth");
        CompanyTokenSecret secret = new CompanyTokenSecret("unit-test-secret-unit-test-secret-unit", tempDir.toString());
        issuer = new CompanyTokenIssuer(secret);
        userService = new FakeUserService();
    }

    @Test
    void passwordHashIsNotPlaintextAndMatches() {
        String hash = hasher.hash("secret-password");
        assertFalse(hash.contains("secret-password"));
        assertTrue(hasher.matches("secret-password", hash));
        assertFalse(hasher.matches("wrong", hash));
    }

    @Test
    void issuedTokenBindsUidAndOidAndSkipsRefresh() {
        TokenVO vo = issuer.issue(1L);
        assertEquals(0L, vo.getExp());
        assertTrue(vo.getToken().length() >= 100);
        DecodedJWT jwt = JWT.decode(vo.getToken());
        assertEquals(1L, jwt.getClaim("uid").asLong());
        assertEquals(1L, jwt.getClaim("oid").asLong());
        assertTrue(jwt.getClaims().containsKey("uid"));
        assertTrue(jwt.getClaims().containsKey("oid"));
        assertFalse(jwt.getClaims().containsKey("account_role"));
        assertFalse(jwt.getClaims().containsKey("role"));
    }

    @Test
    void bootstrapCreatesAdminOnceAndDoesNotOverwrite() {
        CompanyBiBootstrapper bootstrapper = new CompanyBiBootstrapper(userService, hasher);
        bootstrapper.ensureBootstrapAdmin("admin", "系统管理员", "once-only");
        assertEquals(1, userService.insertCount);
        assertEquals(1L, userService.byId.get(1L).getId());
        String hash = userService.byId.get(1L).getPasswordHash();

        userService.byId.get(1L).setDisplayName("already-set");
        bootstrapper.ensureBootstrapAdmin("admin", "新名字", "new-password");
        assertEquals(1, userService.insertCount);
        assertEquals("already-set", userService.byId.get(1L).getDisplayName());
        assertEquals(hash, userService.byId.get(1L).getPasswordHash());
    }

    @Test
    void bootstrapFailsFastWhenPasswordMissing() {
        CompanyBiBootstrapper bootstrapper = new CompanyBiBootstrapper(userService, hasher);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> bootstrapper.ensureBootstrapAdmin("admin", "系统管理员", "  "));
        assertTrue(ex.getMessage().contains("COMPANY_BI_BOOTSTRAP_PASSWORD"));
        assertEquals(0, userService.insertCount);
    }

    @Test
    void loginSuccessIssuesTokenForUid() {
        userService.put(enabledAdmin(hasher.hash("ok")));
        CompanyLoginService loginService = new CompanyLoginService(userService, hasher, issuer);
        TokenVO vo = loginService.login("admin", "ok");
        assertEquals(1L, JWT.decode(vo.getToken()).getClaim("uid").asLong());
        assertEquals(1L, JWT.decode(vo.getToken()).getClaim("oid").asLong());
        assertEquals(1, userService.lastLoginCount);
    }

    @Test
    void loginRejectsWrongPasswordUnknownUserAndDisabled() {
        CompanyLoginService loginService = new CompanyLoginService(userService, hasher, issuer);
        assertLoginCode(() -> loginService.login("nobody", "x"), ResultCode.USER_LOGIN_ERROR);

        userService.put(enabledAdmin(hasher.hash("ok")));
        assertLoginCode(() -> loginService.login("admin", "bad"), ResultCode.USER_LOGIN_ERROR);

        userService.byId.get(1L).setStatus(CompanyUserStatus.DISABLED.name());
        assertLoginCode(() -> loginService.login("admin", "ok"), ResultCode.USER_ACCOUNT_FORBIDDEN);
        assertEquals(0, userService.lastLoginCount);
    }

    @Test
    void disabledUserIsRejectedByGlobalGuard() {
        CompanyBiUser disabled = enabledAdmin("hash");
        disabled.setStatus(CompanyUserStatus.DISABLED.name());
        userService.put(disabled);
        CompanyUserStatusGuard guard = new CompanyUserStatusGuard(userService);
        DEException ex = assertThrows(DEException.class, () -> guard.requireActive(1L));
        assertEquals(401, ex.getCode());
        assertThrows(DEException.class, () -> guard.requireActive(99L));
        assertNull(userService.findById(99L));
    }

    private static CompanyBiUser enabledAdmin(String passwordHash) {
        CompanyBiUser user = new CompanyBiUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setDisplayName("系统管理员");
        user.setPasswordHash(passwordHash);
        user.setAccountRole(CompanyAccountRole.ADMIN.name());
        user.setStatus(CompanyUserStatus.ENABLED.name());
        return user;
    }

    private static void assertLoginCode(Runnable action, ResultCode expected) {
        DEException ex = assertThrows(DEException.class, action::run);
        assertEquals(expected.code(), ex.getCode());
        assertEquals(expected.message(), ex.getMessage());
    }

    static final class FakeUserService extends CompanyBiUserService {
        final Map<Long, CompanyBiUser> byId = new HashMap<>();
        final Map<String, CompanyBiUser> byName = new HashMap<>();
        int insertCount;
        int lastLoginCount;

        FakeUserService() {
            super(null);
        }

        void put(CompanyBiUser user) {
            byId.put(user.getId(), user);
            byName.put(user.getUsername(), user);
        }

        @Override
        public CompanyBiUser findById(Long id) {
            return id == null ? null : byId.get(id);
        }

        @Override
        public CompanyBiUser findByUsername(String username) {
            return username == null ? null : byName.get(username);
        }

        @Override
        public void insert(CompanyBiUser user) {
            insertCount++;
            put(user);
        }

        @Override
        public void updateLastLogin(Long userId, long epochMillis) {
            lastLoginCount++;
            CompanyBiUser user = byId.get(userId);
            if (user != null) {
                user.setLastLoginAt(epochMillis);
            }
        }
    }
}
