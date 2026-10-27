package io.dataease.company.bi.feishu;

import io.dataease.company.bi.integration.IntegrationConnectionStore;
import io.dataease.exception.DEException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanyFeishuConnectionServiceTest {
    private final IntegrationConnectionStore store = mock(IntegrationConnectionStore.class);
    private final CompanyFeishuRepository legacy = mock(CompanyFeishuRepository.class);
    private final CompanyFeishuConnectionService service = new CompanyFeishuConnectionService(store, legacy, () -> 1234L);
    @Test void emptyInstallIsDisabledAndDoesNotExposeSecrets() {
        Map<String, Object> result = service.get();
        assertEquals(false, result.get("configured"));
        assertEquals(false, result.get("runtimeEnabled"));
        assertFalse(result.containsKey("deliveryEnabled"));
        assertFalse(result.containsKey("schedulerEnabled"));
        assertFalse(result.containsKey("appSecret"));
    }
    @Test void preservesBlankSecretsAndReturnsOnlyPresenceFlags() {
        var config = Map.<String, Object>of("appId", "cli_test", "runtimeEnabled", true);
        when(store.find("FEISHU", "default")).thenReturn(new IntegrationConnectionStore.Connection(config,
                Map.of("appSecret", "old-secret", "screenshotPassword", "old-password")));
        service.save(Map.of("appId", "cli_test", "appSecret", "", "screenshotPassword", ""), 1L);
        verify(store).save(eq("FEISHU"), eq("default"), anyMap(),
                eq(Map.of("appSecret", "old-secret", "screenshotPassword", "old-password")), eq(1L));
        String result = service.get().toString();
        assertFalse(result.contains("old-secret")); assertFalse(result.contains("old-password"));
    }
    @Test void rejectsRemovedSimulationControlsAndMalformedConfiguration() {
        assertThrows(DEException.class, () -> service.save(Map.of("appId", "cli_test", "appSecret", "test", "deliveryEnabled", true), 1));
        assertThrows(DEException.class, () -> service.save(Map.of("appId", "cli_test", "appSecret", "test", "runtimeEnabled", "true"), 1));
        assertThrows(DEException.class, () -> service.save(Map.of("appId", "cli_test", "appSecret", "test", "databaseName", "hard-coded"), 1));
        verify(store, never()).save(anyString(), anyString(), anyMap(), anyMap(), anyLong());
    }
    @Test void directoryConnectionDoesNotRequireScreenshotAccountButSubscriptionExecutionDoes() {
        when(store.find("FEISHU", "default")).thenReturn(new IntegrationConnectionStore.Connection(
                Map.of("appId", "cli_test", "runtimeEnabled", true), Map.of("appSecret", "secret")));
        assertThrows(DEException.class, service::requireExecutionReady);
        when(store.find("FEISHU", "default")).thenReturn(new IntegrationConnectionStore.Connection(
                Map.of("appId", "cli_test", "runtimeEnabled", true, "screenshotUser", "capture"),
                Map.of("appSecret", "secret", "screenshotPassword", "password")));
        assertDoesNotThrow(service::requireExecutionReady);
        assertFalse(service.get().containsKey("deliveryEnabled"));
        assertFalse(service.get().containsKey("schedulerEnabled"));
    }
    @Test void changingAppDoesNotReassignExistingRecipients() {
        var app = new CompanyFeishuRepository.AppConfig(); app.appId = "old_app";
        when(legacy.findAppConfig()).thenReturn(app);
        var subscription = new CompanyFeishuRepository.Subscription(); subscription.status = "ENABLED";
        when(legacy.listSubscriptions()).thenReturn(List.of(subscription));
        assertThrows(DEException.class, () -> service.save(Map.of("appId", "new_app", "appSecret", "new-secret"), 1));
        verify(legacy, never()).updateRecipient(any());
    }
    @Test void connectionControllerKeepsBootstrapOnlyCredentialMutation() {
        var guard = mock(io.dataease.company.bi.admin.CompanyAdminGuard.class);
        var worker = mock(CompanyFeishuWorker.class);
        var controller = new CompanyFeishuConnectionController(guard, service, worker);
        doThrow(new IllegalStateException("denied")).when(guard).requireBootstrapAdmin();
        assertThrows(IllegalStateException.class, () -> controller.save(new HashMap<>()));
        assertThrows(IllegalStateException.class, controller::test);
        verifyNoInteractions(worker);
        verify(store, never()).save(anyString(), anyString(), anyMap(), anyMap(), anyLong());
    }
    @Test void embeddedBridgeKeepsAuditIdentifiersAsStrings() {
        var worker = mock(CompanyFeishuWorker.class);
        new CompanyFeishuEmbeddedGateway(worker).start(7500000000000000456L, 7500000000000000122L, true);
        verify(worker).call("start", Map.of("subscriptionId", "7500000000000000456",
                "operatorUserId", "7500000000000000122", "dryRun", true));
    }
}
