package ru.bright.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import ru.bright.model.Event;
import ru.bright.repository.CouchLibraryRepository;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class DocumentController {
    private final CouchLibraryRepository repository;
    public DocumentController(CouchLibraryRepository repository) { this.repository = repository; }
    public record UpdateEvent(@NotBlank String rev, @NotBlank String actor,
            @Pattern(regexp="OPEN|CLOSED|CANCELLED") @NotNull String state,
            @NotBlank String title, @NotBlank String type, @NotNull Instant startsAt, @Min(1) int capacity) {}
    @GetMapping("/events/{id}/document")
    public JsonNode document(@PathVariable UUID id) { return repository.eventDocument(id); }
    @PutMapping("/events/{id}")
    public JsonNode update(@PathVariable UUID id, @Valid @RequestBody UpdateEvent body) {
        return repository.updateEvent(id, body.rev(), new Event(id, body.title(), body.type(), body.startsAt(), body.capacity(), Instant.EPOCH), body.actor(), body.state());
    }
    @DeleteMapping("/events/{id}")
    public JsonNode delete(@PathVariable UUID id, @RequestParam String rev, @RequestParam(defaultValue="system") String actor) {
        if (actor.isBlank() || rev.isBlank()) throw new IllegalArgumentException("actor and rev are required");
        return repository.deleteEvent(id, rev, actor);
    }
    @GetMapping("/events/{id}/orders")
    public JsonNode orders(@PathVariable UUID id, @RequestParam(defaultValue="25") int limit, @RequestParam(required=false) String bookmark) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be 1..200");
        return repository.relatedOrders(id, limit, bookmark);
    }
    @GetMapping("/analytics/user-actions")
    public JsonNode actions() { return repository.userActions(); }
    @GetMapping({"/documents/events", "/documents/events/explain"})
    public JsonNode search(@RequestParam String type, @RequestParam(required=false) Instant from,
            @RequestParam(required=false) Instant to, @RequestParam(required=false) String actor,
            @RequestParam(required=false) String state, @RequestParam(defaultValue="asc") String sort,
            @RequestParam(defaultValue="25") int limit, @RequestParam(required=false) String bookmark,
            jakarta.servlet.http.HttpServletRequest request) {
        return repository.search(type, from, to, actor, state, sort, limit, bookmark, request.getRequestURI().endsWith("/explain"));
    }
}
