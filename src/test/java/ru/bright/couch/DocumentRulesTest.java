package ru.bright.couch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DocumentRulesTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void timestampsUseFixedWidthUtcForLexicalSorting() {
        assertThat(DocumentRules.time(java.time.Instant.parse("2026-01-01T00:00:00Z")))
            .isEqualTo("2026-01-01T00:00:00.000000000Z");
    }
    @Test void historyAppendsAndCapturesActor() throws Exception {
        var doc = json.createObjectNode().put("kind", "event");
        DocumentRules.change(doc, "alice", "CREATE", "OPEN", "2026-01-01T00:00:00.000000000Z");
        DocumentRules.change(doc, "bob", "UPDATE", "CLOSED", "2026-01-02T00:00:00.000000000Z");
        assertThat(doc.path("history").size()).isEqualTo(2);
        assertThat(doc.at("/lastModified/by").asText()).isEqualTo("bob");
        assertThat(doc.at("/history/0/state").asText()).isEqualTo("OPEN");
    }
}
