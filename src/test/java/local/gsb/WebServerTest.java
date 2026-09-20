package local.gsb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebServerTest {
    @TempDir
    Path dataDirectory;

    @Test
    void servesPageAndPersistsBatchThroughHttp() throws Exception {
        AppService service = new AppService(dataDirectory,
                Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC));
        WebServer server = WebServer.start(service, 0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> page = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("Pair-wise GSB"));

            Map<String, Object> batch = new LinkedHashMap<>();
            batch.put("idempotencyKey", "http-1");
            batch.put("commands", List.of(
                    Map.of("type", "create-context", "branchId", "main", "id", "A", "label", "A", "source", "x"),
                    Map.of("type", "create-context", "branchId", "main", "id", "B", "label", "B", "source", "x"),
                    Map.of("type", "add-relation", "id", "r1", "evidenceKey", "r1", "branchId", "main",
                            "fromContextId", "A", "toContextId", "B", "relation", "earlier-than",
                            "source", "book", "pages", "1", "strength", "strong")
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/api/batch"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(batch)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(202, response.statusCode());

            client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/api/wait-jobs"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> branch = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/api/branches/main"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, branch.statusCode());
            assertTrue(branch.body().contains("\"status\":\"feasible\""));
            assertTrue(branch.body().contains("\"kind\":\"direct\""));
        } finally {
            server.stop();
            service.stop();
        }
    }
}
