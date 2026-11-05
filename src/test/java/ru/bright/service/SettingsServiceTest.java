package ru.bright.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.bright.TestObjects;
import ru.bright.CouchTestSupport;
import ru.bright.model.ManagerSettings;
import ru.bright.repository.InMemoryEtcdGateway;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsServiceTest extends CouchTestSupport {
    private InMemoryEtcdGateway gateway;
    private SettingsService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC);
        gateway = new InMemoryEtcdGateway(clock);
        service = new SettingsService(repository, gateway, TestObjects.jsonConverter(), 60);
    }

    @Test
    void cachesSettingsAndInvalidatesCacheOnUpdate() {
        ManagerSettings initial = new ManagerSettings("manager-1", 4);
        service.saveSettings(initial);

        assertThat(service.getSettings("manager-1")).contains(initial);
        repository.saveSettings(new ManagerSettings("manager-1", 6));
        assertThat(service.getSettings("manager-1")).contains(initial);

        ManagerSettings updated = new ManagerSettings("manager-1", 3);
        service.saveSettings(updated);
        assertThat(service.getSettings("manager-1")).contains(updated);
    }

    @Test
    void cacheExpiresAndReloadsCouchSettings() {
        service.saveSettings(new ManagerSettings("manager-1", 4));
        assertThat(service.getSettings("manager-1")).contains(new ManagerSettings("manager-1", 4));
        repository.saveSettings(new ManagerSettings("manager-1", 6));
        gateway.advanceSeconds(61);
        assertThat(service.getSettings("manager-1")).contains(new ManagerSettings("manager-1", 6));
    }

    @Test
    void settingsRemainAvailableWhenEtcdCacheIsDown() {
        repository.saveSettings(new ManagerSettings("manager-1", 4));
        var failedGateway = org.mockito.Mockito.spy(gateway);
        org.mockito.Mockito.doThrow(new ru.bright.exception.StorageUnavailableException("offline", null))
                .when(failedGateway).get(org.mockito.ArgumentMatchers.anyString());
        var fallback = new SettingsService(repository, failedGateway, TestObjects.jsonConverter(), 60);
        assertThat(fallback.getSettings("manager-1")).contains(new ManagerSettings("manager-1", 4));
    }
}
