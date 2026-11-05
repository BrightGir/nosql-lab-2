package ru.bright.service;

import org.springframework.stereotype.Service;
import ru.bright.exception.ResourceConflictException;
import ru.bright.exception.ResourceNotFoundException;
import ru.bright.model.DraftOrder;
import ru.bright.model.ManagerSettings;
import ru.bright.model.Order;
import ru.bright.repository.EtcdGateway;
import ru.bright.repository.EtcdKeys;
import ru.bright.repository.JsonConverter;
import ru.bright.repository.StoredValue;
import ru.bright.repository.CouchLibraryRepository;
import ru.bright.exception.StorageUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final CouchLibraryRepository repository;
    private final EtcdGateway gateway;
    private final JsonConverter json;
    private final Clock clock;
    private final SettingsService settingsService;

    public OrderService(CouchLibraryRepository repository, EtcdGateway gateway, JsonConverter json, Clock clock, SettingsService settingsService) {
        this.repository = repository;
        this.gateway = gateway;
        this.json = json;
        this.clock = clock;
        this.settingsService = settingsService;
    }

    public DraftOrder createDraft(UUID eventId, String managerId, Integer quantity, String comment, long ttlSeconds) {
        if (repository.findEvent(eventId).isEmpty()) {
            throw new ResourceNotFoundException("Event not found: " + eventId);
        }
        if (ttlSeconds < 5 || ttlSeconds > 86_400) {
            throw new IllegalArgumentException("TTL must be 5..86400 seconds");
        }

        int resolvedQuantity = quantity != null ? quantity : settingsService.getSettings(managerId)
                .map(ManagerSettings::defaultQuantity)
                .orElse(2);
        DraftOrder draft = new DraftOrder(
                UUID.randomUUID(), eventId, managerId, resolvedQuantity, comment, clock.instant());
        gateway.putWithTtl(EtcdKeys.draft(draft.id()), json.write(draft), ttlSeconds);
        return draft;
    }

    public Optional<DraftOrder> getDraft(UUID id) {
        return gateway.get(EtcdKeys.draft(id)).map(value -> json.read(value.value(), DraftOrder.class));
    }

    public Order submitDraft(UUID draftId) {
        Optional<Order> existing = repository.findOrderByDraft(draftId);
        if (existing.isPresent()) {
            cleanupDraft(draftId);
            return existing.get();
        }
        Optional<StoredValue> storedDraft = gateway.get(EtcdKeys.draft(draftId));
        if (storedDraft.isEmpty()) {
            return repository.findOrderByDraft(draftId).orElseThrow(() ->
                    new ResourceConflictException("Draft is missing or expired"));
        }
        DraftOrder draft = json.read(storedDraft.get().value(), DraftOrder.class);
        Order order = new Order(UUID.randomUUID(), draft.id(), draft.eventId(), draft.managerId(),
                draft.quantity(), "CREATED", clock.instant());
        Order saved = repository.createOrder(order);
        cleanupDraft(draftId);
        return saved;
    }

    private void cleanupDraft(UUID draftId) {
        try {
            gateway.delete(EtcdKeys.draft(draftId));
        } catch (StorageUnavailableException exception) {
            log.warn("Order committed; draft {} cleanup deferred to retry or lease expiration", draftId);
        }
    }

    public Optional<Order> getOrder(UUID id) {
        return repository.findOrder(id);
    }
}
