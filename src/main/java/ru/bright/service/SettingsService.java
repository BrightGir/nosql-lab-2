package ru.bright.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.bright.exception.StorageUnavailableException;
import ru.bright.model.ManagerSettings;
import ru.bright.repository.EtcdGateway;
import ru.bright.repository.EtcdKeys;
import ru.bright.repository.CouchLibraryRepository;
import ru.bright.repository.JsonConverter;

import java.util.Optional;

@Service
public class SettingsService {
    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private final CouchLibraryRepository repository;
    private final EtcdGateway gateway;
    private final JsonConverter json;
    private final long cacheTtlSeconds;

    public SettingsService(CouchLibraryRepository repository, EtcdGateway gateway, JsonConverter json,
                           @Value("${app.settings-cache-ttl-seconds:60}") long cacheTtlSeconds) {
        this.repository = repository;
        this.gateway = gateway;
        this.json = json;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public void saveSettings(ManagerSettings settings) {
        repository.saveSettings(settings);
        try {
            gateway.delete(EtcdKeys.settings(settings.managerId()));
        } catch (StorageUnavailableException exception) {
            log.warn("Settings saved in CouchDB; cache invalidation failed for {}", settings.managerId());
        }
    }

    public Optional<ManagerSettings> getSettings(String managerId) {
        try {
            var cached = gateway.get(EtcdKeys.settings(managerId));
            if (cached.isPresent()) {
                return Optional.of(json.read(cached.get().value(), ManagerSettings.class));
            }
        } catch (StorageUnavailableException exception) {
            log.warn("Settings cache unavailable; reading CouchDB");
        }
        Optional<ManagerSettings> loaded = repository.findSettings(managerId);
        loaded.ifPresent(settings -> {
            try {
                gateway.putWithTtl(EtcdKeys.settings(managerId), json.write(settings), cacheTtlSeconds);
            } catch (StorageUnavailableException exception) {
                log.warn("Settings cache population failed for {}", managerId);
            }
        });
        return loaded;
    }
}
