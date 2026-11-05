package ru.bright.couch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.bright.exception.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Component
public class CouchClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    private final String base;
    private final String authorization;
    public CouchClient(ObjectMapper json, @Value("${app.couch.url}") String url,
                       @Value("${app.couch.database}") String database,
                       @Value("${app.couch.username}") String username,
                       @Value("${app.couch.password}") String password) {
        this.json = json;
        base = url.replaceAll("/$", "") + "/" + encode(database);
        authorization = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
    public static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    public JsonNode request(String method, String path, JsonNode body) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                    .header("Authorization", authorization).header("Content-Type", "application/json");
            var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) throw new ResourceNotFoundException("CouchDB document not found");
            if (response.statusCode() == 409) throw new ResourceConflictException("Revision conflict; reload the document and retry with its current _rev");
            if (response.statusCode() == 403) throw new IllegalArgumentException("CouchDB rejected document: " + response.body());
            if (response.statusCode() >= 400) throw new StorageUnavailableException("CouchDB HTTP " + response.statusCode(), null);
            return json.readTree(response.body());
        } catch (java.io.IOException e) {
            throw new StorageUnavailableException("CouchDB connection failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageUnavailableException("CouchDB request interrupted", e);
        }
    }
}
