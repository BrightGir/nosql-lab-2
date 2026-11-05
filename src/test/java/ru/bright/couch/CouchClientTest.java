package ru.bright.couch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import ru.bright.exception.*;
import java.net.InetSocketAddress;
import static org.assertj.core.api.Assertions.*;

class CouchClientTest {
    private HttpServer server;
    private CouchClient client;
    private int status;
    private String body;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/library", exchange -> {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        client = new CouchClient(new ObjectMapper(), "http://127.0.0.1:"+server.getAddress().getPort(), "library", "user", "password");
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void mapsRevisionConflict() {
        status=409;body="{\"error\":\"conflict\"}";
        assertThatThrownBy(() -> client.request("PUT", "/event:1",new ObjectMapper().createObjectNode()))
                .isInstanceOf(ResourceConflictException.class);
    }
    @Test void mapsUnavailableDatabase() {
        status=500;body="{}";
        assertThatThrownBy(() -> client.request("GET", "/event:1",null)).isInstanceOf(StorageUnavailableException.class);
    }
    @Test void mapsNotFound() {
        status=404;body="{}";
        assertThatThrownBy(() -> client.request("GET", "/event:1",null)).isInstanceOf(ResourceNotFoundException.class);
    }
    @Test void decodesSuccessfulDocument() {
        status=200;body="{\"_rev\":\"2-abc\"}";
        assertThat(client.request("GET", "/event:1",null).path("_rev").asText()).isEqualTo("2-abc");
    }
}
