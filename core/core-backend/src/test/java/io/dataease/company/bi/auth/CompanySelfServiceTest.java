package io.dataease.company.bi.auth;

import io.dataease.api.permissions.user.dto.ModifyPwdRequest;
import io.dataease.auth.bo.TokenUserBO;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.exception.DEException;
import io.dataease.utils.AuthUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompanySelfServiceTest {
    private final CompanyBiUserService users = mock(CompanyBiUserService.class);
    private final CompanyPasswordHasher hasher = new CompanyPasswordHasher();
    private final CompanyPasswordChangeService service = new CompanyPasswordChangeService(users, hasher);

    private CompanyBiUser user() {
        var user = new CompanyBiUser();
        user.setId(7L); user.setUsername("unit-user"); user.setStatus("ENABLED");
        user.setPasswordHash(hasher.hash("Old-Pass-123"));
        return user;
    }

    @AfterEach void cleanup() { AuthUtils.remove(); }

    @Test void persistsNewHashAndOldPasswordStopsMatching() {
        var user = user();
        when(users.updatePassword(eq(7L), eq(user.getPasswordHash()), anyString())).thenAnswer(call -> {
            String hash = call.getArgument(2);
            assertTrue(hasher.matches("New-Pass-456", hash));
            assertFalse(hasher.matches("Old-Pass-123", hash));
            assertNotEquals("New-Pass-456", hash);
            return true;
        });
        service.change(user, "Old-Pass-123", "New-Pass-456");
        verify(users).updatePassword(eq(7L), eq(user.getPasswordHash()), anyString());
    }

    @Test void wrongOldPasswordDoesNotWrite() {
        assertThrows(DEException.class, () -> service.change(user(), "wrong", "New-Pass-456"));
        verifyNoInteractions(users);
    }

    @Test void acceptsEightAnd128CharacterPasswords() {
        when(users.updatePassword(anyLong(), anyString(), anyString())).thenReturn(true);
        service.change(user(), "Old-Pass-123", "Aa1!xxxx");
        service.change(user(), "Old-Pass-123", "Aa1!" + "x".repeat(124));
        verify(users, times(2)).updatePassword(eq(7L), anyString(), anyString());
    }

    @Test void longPasswordsRetainBytesAfterBcryptLimitAndLegacyHashesRemainUsable() {
        String prefix = "Aa1!" + "x".repeat(100);
        String hash = hasher.hash(prefix + "A");
        assertTrue(hasher.matches(prefix + "A", hash));
        assertFalse(hasher.matches(prefix + "B", hash));
        assertTrue(hasher.matches("Old-Pass-123", hasher.hash("Old-Pass-123")));
        assertFalse(hasher.matches(prefix, hasher.hash("Old-Pass-123")));
    }

    @Test void rejectsWeakSameAndOutOfRangePasswords() {
        for (String value : new String[]{"short", "abcdefgh1!", "ABCDEFGH1!", "Abcdefghi!", "Abcdefgh12", "Old-Pass-123", "Aa1!" + "x".repeat(125)}) {
            assertThrows(DEException.class, () -> service.change(user(), "Old-Pass-123", value));
        }
        verifyNoInteractions(users);
    }

    @Test void concurrentPasswordChangeIsNotReportedAsSuccess() {
        when(users.updatePassword(anyLong(), anyString(), anyString())).thenReturn(false);
        assertThrows(DEException.class, () -> service.change(user(), "Old-Pass-123", "New-Pass-456"));
    }

    @Test void disabledUserCannotChangePassword() {
        var user = user(); user.setStatus("DISABLED");
        assertThrows(DEException.class, () -> service.change(user, "Old-Pass-123", "New-Pass-456"));
        verifyNoInteractions(users);
    }

    @Test void controllerRejectsAnotherUidBeforeDecrypting() {
        var user = user(); when(users.findById(7L)).thenReturn(user);
        AuthUtils.setUser(new TokenUserBO(7L, 1L));
        var changes = mock(CompanyPasswordChangeService.class);
        var controller = new CompanyUserServer(users, new CompanyUserStatusGuard(users), changes);
        var request = new ModifyPwdRequest(); request.setUid(1L);
        assertThrows(DEException.class, () -> controller.modifyPwd(request));
        verifyNoInteractions(changes);
    }

    @Test void controllerRejectsAnonymousAndInvalidCiphertext() {
        var changes = mock(CompanyPasswordChangeService.class);
        var controller = new CompanyUserServer(users, new CompanyUserStatusGuard(users), changes);
        assertThrows(DEException.class, () -> controller.modifyPwd(new ModifyPwdRequest()));
        AuthUtils.setUser(new TokenUserBO(7L, 1L));
        when(users.findById(7L)).thenReturn(user());
        var request = new ModifyPwdRequest(); request.setPwd("invalid"); request.setNewPwd("invalid");
        assertThrows(DEException.class, () -> controller.modifyPwd(request));
        verifyNoInteractions(changes);
    }
}
