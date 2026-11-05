package ru.bright.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.bright.TestObjects;
import ru.bright.CouchTestSupport;
import ru.bright.exception.ResourceConflictException;
import ru.bright.model.DraftOrder;
import ru.bright.model.Event;
import ru.bright.model.Order;
import ru.bright.repository.InMemoryEtcdGateway;
import ru.bright.repository.JsonConverter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderServiceTest extends CouchTestSupport {
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");

    private InMemoryEtcdGateway gateway;
    private JsonConverter json;
    private Clock clock;
    private EventService eventService;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        gateway = new InMemoryEtcdGateway(clock);
        json = TestObjects.jsonConverter();
        eventService = new EventService(repository, gateway, clock);
        orderService = new OrderService(repository, gateway, json, clock,
                new SettingsService(repository, gateway, json, 60));
    }

    @Test
    void temporaryDraftExpiresAfterTtl() {
        Event event = eventService.createEvent("Book presentation", "PRESENTATION", NOW, 50);
        DraftOrder draft = orderService.createDraft(event.id(), "manager-1", 2, "front row", 30);

        assertThat(orderService.getDraft(draft.id())).contains(draft);
        gateway.advanceSeconds(31);
        assertThat(orderService.getDraft(draft.id())).isEmpty();
    }

    @Test
    void submittingDraftCreatesOrderAndRetryReturnsSameOrder() {
        Event event = eventService.createEvent("Rare books", "EXHIBITION", NOW, 100);
        DraftOrder draft = orderService.createDraft(event.id(), "manager-1", 3, "student group", 60);

        Order order = orderService.submitDraft(draft.id());

        assertThat(order.eventId()).isEqualTo(event.id());
        assertThat(order.status()).isEqualTo("CREATED");
        assertThat(orderService.getDraft(draft.id())).isEmpty();
        assertThat(orderService.getOrder(order.id())).contains(order);
        assertThat(orderService.submitDraft(draft.id())).isEqualTo(order);
    }

    @Test
    void concurrentSubmissionsReturnOneOrder() {
        Event event = eventService.createEvent("Books", "MEETING", NOW, 50);
        DraftOrder draft = orderService.createDraft(event.id(), "manager-1", 2, "", 60);
        var orders = java.util.stream.IntStream.range(0, 20).parallel()
                .mapToObj(i -> orderService.submitDraft(draft.id())).toList();
        assertThat(orders.stream().map(Order::id).distinct()).hasSize(1);
    }

    @Test
    void couchFailureDoesNotConsumeDraft() {
        Event event = eventService.createEvent("Books", "MEETING", NOW, 50);
        DraftOrder draft = orderService.createDraft(event.id(), "manager-1", 2, "", 60);
        var failedRepository = org.mockito.Mockito.spy(org.springframework.test.util.AopTestUtils.<ru.bright.repository.CouchLibraryRepository>getUltimateTargetObject(repository));
        org.mockito.Mockito.doThrow(new ru.bright.exception.StorageUnavailableException("offline", null))
                .when(failedRepository).createOrder(org.mockito.ArgumentMatchers.any());
        var service = new OrderService(failedRepository, gateway, json, clock,
                new SettingsService(repository, gateway, json, 60));
        assertThatThrownBy(() -> service.submitDraft(draft.id()))
                .isInstanceOf(ru.bright.exception.StorageUnavailableException.class);
        assertThat(orderService.getDraft(draft.id())).contains(draft);
    }

    @Test
    void cleanupFailureAfterCouchCommitDoesNotLoseOrderOrCreateDuplicate() {
        Event event = eventService.createEvent("Books", "MEETING", NOW, 50);
        DraftOrder draft = orderService.createDraft(event.id(), "manager-1", 2, "", 60);
        var failedGateway = org.mockito.Mockito.spy(gateway);
        org.mockito.Mockito.doThrow(new ru.bright.exception.StorageUnavailableException("offline", null))
                .when(failedGateway).delete(org.mockito.ArgumentMatchers.anyString());
        var service = new OrderService(repository, failedGateway, json, clock,
                new SettingsService(repository, gateway, json, 60));
        Order order = service.submitDraft(draft.id());
        assertThat(service.submitDraft(draft.id())).isEqualTo(order);
        assertThat(repository.findOrderByDraft(draft.id())).contains(order);
        assertThat(orderService.getDraft(draft.id())).contains(draft);
    }

    @Test
    void expiredDraftCannotBeSubmitted() {
        Event event = eventService.createEvent("Books", "MEETING", NOW, 50);
        DraftOrder draft = orderService.createDraft(event.id(), "manager-1", 2, "", 5);
        gateway.advanceSeconds(6);
        assertThatThrownBy(() -> orderService.submitDraft(draft.id()))
                .isInstanceOf(ResourceConflictException.class);
        assertThat(repository.findOrderByDraft(draft.id())).isEmpty();
    }
}
