package ru.bright.couch;
import org.junit.jupiter.api.Test;
import ru.bright.CouchTestSupport;
import ru.bright.model.Event;
import ru.bright.exception.ResourceConflictException;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class RevisionTest extends CouchTestSupport {
    @Test void staleUpdateCannotOverwriteHistoryAndDeleteIsLogical() {
        var event = new Event(UUID.randomUUID(),"Book","MEETING",Instant.now(),10,Instant.now());
        repository.saveEvent(event);
        String rev=repository.eventDocument(event.id()).path("_rev").asText();
        var updated=repository.updateEvent(event.id(),rev,event,"alice","CLOSED");
        assertThat(updated.path("history").size()).isEqualTo(2);
        assertThatThrownBy(()->repository.updateEvent(event.id(),rev,event,"bob","OPEN"))
            .isInstanceOf(ResourceConflictException.class);
        repository.deleteEvent(event.id(),updated.path("_rev").asText(),"alice");
        assertThat(repository.findEvent(event.id())).isEmpty();
        assertThat(repository.get("event:"+event.id()).orElseThrow().path("history").size()).isEqualTo(3);
    }
}
