package gsb.web;

import gsb.model.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class HttpServer {
    private final Service service;
    private com.sun.net.httpserver.HttpServer server;

    public HttpServer(Service service) {
        this.service = service;
    }

    public void start(int port) throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", new RootHandler());
        server.setExecutor(null);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                route(exchange);
            } catch (ApiException exception) {
                send(exchange, exception.status(), exception.body());
            } catch (IllegalArgumentException exception) {
                send(exchange, 400, Map.of("error", Map.of("code", "BAD_REQUEST", "message", exception.getMessage())));
            } catch (Exception exception) {
                exception.printStackTrace();
                send(exchange, 500, Map.of("error", Map.of("code", "INTERNAL_ERROR", "message", exception.getMessage())));
            } finally {
                exchange.close();
            }
        }

        private void route(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method) && "/".equals(path)) {
                staticFile(exchange, "index.html", "text/html; charset=utf-8");
                return;
            }
            if ("GET".equals(method) && "/app.js".equals(path)) {
                staticFile(exchange, "app.js", "application/javascript; charset=utf-8");
                return;
            }
            if ("GET".equals(method) && "/styles.css".equals(path)) {
                staticFile(exchange, "styles.css", "text/css; charset=utf-8");
                return;
            }
            if ("GET".equals(method) && "/api/state".equals(path)) {
                send(exchange, 200, service.stateJson());
                return;
            }
            if ("POST".equals(method) && "/api/branches".equals(path)) {
                send(exchange, 200, service.createBranch(body(exchange)));
                return;
            }
            if ("GET".equals(method) && path.startsWith("/api/branches/") && path.endsWith("/compare") == false && path.endsWith("/audit") == false && path.endsWith("/export") == false) {
                String branch = path.substring("/api/branches/".length());
                send(exchange, 200, service.branchJson(branch, true));
                return;
            }
            if ("POST".equals(method) && path.startsWith("/api/branches/") && path.endsWith("/batch")) {
                String branch = path.substring("/api/branches/".length(), path.length() - "/batch".length());
                send(exchange, 200, service.batch(branch, body(exchange)));
                return;
            }
            if ("POST".equals(method) && path.startsWith("/api/branches/") && path.endsWith("/publish")) {
                String branch = path.substring("/api/branches/".length(), path.length() - "/publish".length());
                send(exchange, 200, service.publish(branch, body(exchange)));
                return;
            }
            if ("POST".equals(method) && path.startsWith("/api/branches/") && path.endsWith("/merge")) {
                String branch = path.substring("/api/branches/".length(), path.length() - "/merge".length());
                send(exchange, 200, service.merge(branch, body(exchange)));
                return;
            }
            if ("GET".equals(method) && path.startsWith("/api/branches/") && path.endsWith("/audit")) {
                String branch = path.substring("/api/branches/".length(), path.length() - "/audit".length());
                send(exchange, 200, service.audit(branch));
                return;
            }
            if ("GET".equals(method) && path.startsWith("/api/branches/") && path.endsWith("/export")) {
                String branch = path.substring("/api/branches/".length(), path.length() - "/export".length());
                send(exchange, 200, service.export(branch));
                return;
            }
            if ("POST".equals(method) && path.startsWith("/api/branches/") && path.endsWith("/jobs")) {
                String branch = path.substring("/api/branches/".length(), path.length() - "/jobs".length());
                send(exchange, 202, service.startJob(branch, body(exchange)));
                return;
            }
            if ("GET".equals(method) && path.startsWith("/api/jobs/")) {
                send(exchange, 200, service.job(path.substring("/api/jobs/".length())));
                return;
            }
            if ("GET".equals(method) && "/api/compare".equals(path)) {
                String query = exchange.getRequestURI().getQuery();
                List<String> branches = query == null || !query.contains("branch=") ? List.of()
                        : java.util.Arrays.stream(query.split("&")).filter(item -> item.startsWith("branch="))
                        .map(item -> item.substring("branch=".length())).map(java.net.URLDecoder::decode).toList();
                send(exchange, 200, service.compare(branches));
                return;
            }
            send(exchange, 404, Map.of("error", Map.of("code", "NOT_FOUND", "message", path)));
        }

        private Map<String, Object> body(HttpExchange exchange) throws IOException {
            try (InputStream input = exchange.getRequestBody()) {
                String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                return Json.object(Json.parse(text.isBlank() ? "{}" : text));
            }
        }

        private void send(HttpExchange exchange, int status, Object value) throws IOException {
            byte[] bytes = Json.writePretty(value).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        private void staticFile(HttpExchange exchange, String name, String contentType) throws IOException {
            if (name.contains("..") || name.contains("/") || name.contains("\\")) {
                send(exchange, 404, Map.of("error", Map.of("code", "NOT_FOUND", "message", name)));
                return;
            }
            try (InputStream input = RootHandler.class.getResourceAsStream("/static/" + name)) {
                if (input == null) {
                    send(exchange, 404, Map.of("error", Map.of("code", "NOT_FOUND", "message", name)));
                    return;
                }
                byte[] bytes = input.readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
            }
        }
    }
}
