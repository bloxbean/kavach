package com.bloxbean.cardano.kavach.demo;

import com.bloxbean.cardano.client.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Local-only wallet demo API. The service builds and evaluates transactions but never owns wallet spending
 * keys. Mutations require explicit JSON POSTs and wallet proofs/witnesses; there is no mainnet
 * deployment mode. Keep the port bound to loopback, behind Vite's same-origin proxy.
 */
public final class DemoServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ORIGINS =
            Set.of("http://localhost:6670", "http://127.0.0.1:6670");

    /** Starts the local API on 127.0.0.1:8095; configuration is controlled by the operator. */
    public static void main(String[] args) throws Exception {
        var service = new DemoService();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8095), 32);
        server.createContext("/api/", exchange -> handle(exchange, service));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Kavach demo API: http://127.0.0.1:8095 (DevKit only; no wallet spending keys)");
    }

    private static void handle(HttpExchange exchange, DemoService service) {
        int status = 200;
        Object result;
        try {
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!Set.of("localhost:6670", "127.0.0.1:6670", "localhost:8095", "127.0.0.1:8095")
                    .contains(host)) throw new IllegalArgumentException("Untrusted local host");
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !ORIGINS.contains(origin))
                throw new IllegalArgumentException("Untrusted browser origin");
            String path = exchange.getRequestURI().getPath().substring(5);
            String method = exchange.getRequestMethod();
            if (method.equals("GET") && path.equals("status")) result = service.status();
            else if (method.equals("GET") && path.equals("companion/pairing")) result = service.companionPairing();
            else if (method.equals("GET") && path.matches("plans/[0-9a-f-]{36}"))
                result = service.plan(path.substring(6));
            else if (method.equals("POST")) {
                if (!"application/json"
                        .equals(exchange.getRequestHeaders().getFirst("Content-Type")))
                    throw new IllegalArgumentException("JSON required");
                byte[] bytes = exchange.getRequestBody().readNBytes(65537);
                if (bytes.length > 65536) throw new IllegalArgumentException("Request too large");
                Map<String, Object> body = JSON.readValue(bytes, new TypeReference<>() {});
                result =
                        switch (path) {
                            case "accounts/restore" -> service.restore(string(body, "locator"));
                            case "plans" -> service.prepare(body);
                            case "wallet/enrollment" -> service.enrollment(body);
                            default -> {
                                String[] segments = path.split("/");
                                if (segments.length != 3 || !segments[0].equals("plans"))
                                    throw new IllegalArgumentException("Unknown endpoint");
                                yield service.update(segments[1], segments[2], body);
                            }
                        };
            } else {
                status = 404;
                result = Map.of("error", "Unknown endpoint");
            }
        } catch (IllegalArgumentException error) {
            status = 400;
            result =
                    Map.of(
                            "error",
                            error.getMessage() == null ? "Invalid request" : error.getMessage());
        } catch (Exception error) {
            status = 503;
            result =
                    Map.of(
                            "error",
                            error.getMessage() == null
                                    ? "Backend operation failed"
                                    : error.getMessage());
        }
        try {
            byte[] response = JsonUtil.getPrettyJson(result).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
        } catch (Exception ignored) {
            /* A disconnected local client must not affect another request. */
        } finally {
            exchange.close();
        }
    }

    static String string(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String text)) throw new IllegalArgumentException("Missing " + key);
        return text;
    }

    private DemoServer() {}
}
