package ru.bright;

import org.junit.jupiter.api.BeforeEach;
import ru.bright.repository.CouchLibraryRepository;
import ru.bright.couch.CouchClient;
import ru.bright.exception.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Service test double, not a replacement for the real CouchDB integration runner. */
public abstract class CouchTestSupport {
    protected CouchLibraryRepository repository;
    @BeforeEach void setupRepository() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Map<String,ObjectNode> docs = new HashMap<>();
        var client = new CouchClient(mapper, "http://localhost", "unused", "", "") {
            @Override public synchronized JsonNode request(String method, String path, JsonNode body) {
                String id = URLDecoder.decode(path.substring(1), StandardCharsets.UTF_8);
                if (method.equals("GET")) {
                    if (!docs.containsKey(id)) throw new ResourceNotFoundException("missing");
                    return docs.get(id).deepCopy();
                }
                if (method.equals("PUT")) {
                    var old = docs.get(id);
                    if (old != null && !old.path("_rev").asText().equals(body.path("_rev").asText()))
                        throw new ResourceConflictException("conflict");
                    var next = ((ObjectNode)body).deepCopy();
                    String rev = UUID.randomUUID().toString();
                    next.put("_rev", rev); docs.put(id, next);
                    return mapper.createObjectNode().put("rev", rev);
                }
                var response = mapper.createObjectNode();
                var results = response.putArray("docs");
                docs.values().stream().filter(d -> d.path("kind").asText().equals(body.at("/selector/kind").asText()))
                    .filter(d -> !body.at("/selector/draftId").isTextual() || d.path("draftId").asText().equals(body.at("/selector/draftId").asText()))
                    .filter(d -> !d.path("state").asText().equals("DELETED")).forEach(d -> results.add(d.deepCopy()));
                return response;
            }
        };
        repository = new CouchLibraryRepository(client, mapper);
    }
}
