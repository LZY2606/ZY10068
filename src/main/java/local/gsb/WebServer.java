package local.gsb;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

final class WebServer {
    private final AppService service;
    private final HttpServer server;

    private WebServer(AppService service, int port) throws IOException {
        this.service = service;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    static WebServer start(AppService service, int port) throws IOException {
        return new WebServer(service, port);
    }

    int port() {
        return server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (IllegalArgumentException ex) {
            send(exchange, 400, Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            send(exchange, 500, Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            send(exchange, 200, "text/html; charset=utf-8", StaticAssets.indexHtml());
            return;
        }
        if ("GET".equals(method) && "/app.js".equals(path)) {
            send(exchange, 200, "application/javascript; charset=utf-8", StaticAssets.appJs());
            return;
        }
        if ("GET".equals(method) && "/styles.css".equals(path)) {
            send(exchange, 200, "text/css; charset=utf-8", StaticAssets.stylesCss());
            return;
        }
        if ("POST".equals(method) && "/api/batch".equals(path)) {
            send(exchange, 202, service.batch(Json.object(Json.parse(readBody(exchange)))));
            return;
        }
        if ("GET".equals(method) && "/api/state".equals(path)) {
            send(exchange, 200, service.stateView());
            return;
        }
        if ("GET".equals(method) && path.startsWith("/api/branches/")) {
            String branchId = path.substring("/api/branches/".length());
            send(exchange, 200, service.branchView(branchId));
            return;
        }
        if ("GET".equals(method) && path.startsWith("/api/snapshots/")) {
            String snapshotId = path.substring("/api/snapshots/".length());
            send(exchange, 200, service.snapshotView(snapshotId));
            return;
        }
        if ("POST".equals(method) && "/api/merge-preview".equals(path)) {
            send(exchange, 200, service.mergePreview(Json.object(Json.parse(readBody(exchange)))));
            return;
        }
        if ("POST".equals(method) && "/api/compare".equals(path)) {
            Map<String, Object> request = Json.object(Json.parse(readBody(exchange)));
            List<String> branchIds = Json.list(request.get("branchIds")).stream().map(String::valueOf).toList();
            send(exchange, 200, service.compareBranches(branchIds));
            return;
        }
        if ("GET".equals(method) && "/api/export".equals(path)) {
            String branchId = queryParameter(exchange, "branch");
            if (branchId == null) branchId = "main";
            send(exchange, 200, service.export(branchId));
            return;
        }
        if ("GET".equals(method) && "/api/events".equals(path)) {
            String from = queryParameter(exchange, "fromSeq");
            send(exchange, 200, Map.of("events", service.eventOrder(from == null ? null : Long.parseLong(from))));
            return;
        }
        if ("POST".equals(method) && "/api/wait-jobs".equals(path)) {
            try {
                service.waitForJobs(2000);
                send(exchange, 200, Map.of("status", "ready"));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException(ex);
            }
            return;
        }
        send(exchange, 404, Map.of("error", "not found"));
    }

    private static String queryParameter(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] values = pair.split("=", 2);
            if (values[0].equals(key)) return values.length == 2 ? values[1] : "";
        }
        return null;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        return new String(body, StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", Json.write(value));
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
