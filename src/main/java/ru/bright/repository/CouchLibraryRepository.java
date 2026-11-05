package ru.bright.repository;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Repository;
import ru.bright.couch.*;
import ru.bright.exception.*;
import ru.bright.model.*;
import java.time.Instant;
import java.util.*;

@Repository
public class CouchLibraryRepository {
    private final CouchClient client;
    private final ObjectMapper json;
    public CouchLibraryRepository(CouchClient client, ObjectMapper json) {
        this.client = client;
        this.json = json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
    public Optional<ObjectNode> get(String id) {
        try { return Optional.of((ObjectNode) client.request("GET", "/" + CouchClient.encode(id), null)); }
        catch (ResourceNotFoundException e) { return Optional.empty(); }
    }
    public ObjectNode save(ObjectNode doc) {
        var result = client.request("PUT", "/" + CouchClient.encode(doc.path("_id").asText()), doc);
        doc.put("_rev", result.path("rev").asText());
        return doc;
    }
    private ObjectNode document(Object value, String id, String kind, String actor) {
        ObjectNode doc = json.valueToTree(value);
        doc.put("_id", id).put("kind", kind);
        DocumentRules.change(doc, actor, "CREATE", "OPEN", DocumentRules.time(Instant.now()));
        return doc;
    }
    private <T> T model(JsonNode doc, Class<T> type) { return json.convertValue(doc, type); }
    public void saveEvent(Event event) { save(document(event, "event:" + event.id(), "event", "system")); }
    public ObjectNode eventDocument(UUID id) {
        return get("event:" + id).filter(d -> !d.path("state").asText().equals("DELETED"))
                .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + id));
    }
    public Optional<Event> findEvent(UUID id) {
        return get("event:" + id).filter(d -> !d.path("state").asText().equals("DELETED")).map(d -> model(d, Event.class));
    }
    public List<Event> listEvents() {
        var all = new ArrayList<Event>();
        var q = json.createObjectNode();
        q.putObject("selector").put("kind", "event").putObject("state").put("$ne", "DELETED");
        q.put("limit", 200);
        while (true) {
            var result = client.request("POST", "/_find", q);
            for (var doc : result.path("docs")) all.add(model(doc, Event.class));
            if (result.path("docs").size() < 200) break;
            q.put("bookmark", result.path("bookmark").asText());
        }
        all.sort(Comparator.comparing(Event::createdAt).thenComparing(Event::id));
        return all;
    }
    public ObjectNode updateEvent(UUID id, String rev, Event event, String actor, String state) {
        var doc = eventDocument(id);
        if (!doc.path("_rev").asText().equals(rev)) throw new ResourceConflictException("Stale revision; reload event");
        doc.put("title", event.title()).put("type", event.type()).put("startsAt", event.startsAt().toString()).put("capacity", event.capacity());
        DocumentRules.change(doc, actor, "UPDATE", state, DocumentRules.time(Instant.now()));
        return save(doc);
    }
    public ObjectNode deleteEvent(UUID id, String rev, String actor) {
        var doc = eventDocument(id);
        if (!doc.path("_rev").asText().equals(rev)) throw new ResourceConflictException("Stale revision; reload event");
        // Logical deletion preserves history and references from existing orders.
        DocumentRules.change(doc, actor, "DELETE", "DELETED", DocumentRules.time(Instant.now()));
        return save(doc);
    }
    public Order createOrder(Order input) {
        if (findEvent(input.eventId()).isEmpty()) throw new ResourceNotFoundException("Event not found");
        Order order = new Order(input.draftId(), input.draftId(), input.eventId(), input.managerId(), input.quantity(), input.status(), input.createdAt());
        try { save(document(order, "order:" + order.draftId(), "order", order.managerId())); return order; }
        catch (ResourceConflictException e) { return findOrderByDraft(order.draftId()).orElseThrow(() -> e); }
    }
    public Optional<Order> findOrder(UUID id) { return get("order:" + id).map(d -> model(d, Order.class)); }
    public Optional<Order> findOrderByDraft(UUID id) {
        var direct = findOrder(id);
        if (direct.isPresent()) return direct;
        // Imported lab-1 orders retain their original UUID, distinct from draftId.
        var q = json.createObjectNode();
        q.putObject("selector").put("kind", "order").put("draftId", id.toString());
        q.put("limit", 1);
        var docs = client.request("POST", "/_find", q).path("docs");
        return docs.isEmpty() ? Optional.empty() : Optional.of(model(docs.get(0), Order.class));
    }
    public void saveSettings(ManagerSettings value) {
        for (int attempt = 0; attempt < 10; attempt++) {
            var previous = get("settings:" + value.managerId());
            var doc = previous.orElseGet(() -> document(value, "settings:" + value.managerId(), "settings", value.managerId()));
            if (previous.isPresent()) {
                doc.put("defaultQuantity", value.defaultQuantity());
                DocumentRules.change(doc, value.managerId(), "UPDATE", "OPEN", DocumentRules.time(Instant.now()));
            }
            try { save(doc); return; } catch (ResourceConflictException conflict) { if (attempt == 9) throw conflict; }
        }
    }
    public Optional<ManagerSettings> findSettings(String id) { return get("settings:" + id).map(d -> model(d, ManagerSettings.class)); }
    public JsonNode relatedOrders(UUID id, int limit, String bookmark) {
        var q = json.createObjectNode();
        q.putObject("selector").put("kind", "order").put("eventId", id.toString());
        q.put("limit", limit);
        if (bookmark != null) q.put("bookmark", bookmark);
        return client.request("POST", "/_find", q);
    }
    public JsonNode userActions() { return client.request("GET", "/_design/library/_view/user_actions?group=true", null); }
    public JsonNode search(String type, Instant from, Instant to, String actor, String state, String direction, int limit, String bookmark, boolean explain) {
        if (!Set.of("asc", "desc").contains(direction)) throw new IllegalArgumentException("sort must be asc or desc");
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be 1..200");
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("from must precede to");
        var q = json.createObjectNode();
        var selector = q.putObject("selector").put("kind", "event").put("type", type);
        selector.putObject("state").put("$ne", "DELETED");
        var range = selector.putObject("updatedAt").put("$gte", DocumentRules.time(from == null ? Instant.EPOCH : from));
        if (to != null) range.put("$lte", DocumentRules.time(to));
        if (actor != null || state != null) {
            var nested = selector.putObject("history").putObject("$elemMatch");
            if (actor != null) nested.put("by", actor);
            if (state != null) nested.put("state", state);
        }
        var sort = q.putArray("sort");
        for (String field : List.of("kind", "type", "updatedAt", "_id")) sort.addObject().put(field, direction);
        q.putArray("use_index").add("event-search").add("by-type-updated");
        q.put("limit", limit).put("execution_stats", true);
        if (bookmark != null) q.put("bookmark", bookmark);
        return client.request("POST", explain ? "/_explain" : "/_find", q);
    }
}
