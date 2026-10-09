package com.example.hyperion.ui;

import com.example.hyperion.CacheDiagnostics;
import com.example.hyperion.CacheEntryView;
import com.example.hyperion.CacheStats;
import com.example.hyperion.HyperionCache;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A zero-dependency HTTP server that exposes a live {@link HyperionCache} over a small
 * JSON API and serves the bundled single-page dashboard.
 *
 * <p>Everything here is built on the JDK only: {@link HttpServer} for the transport and
 * {@link Json} for serialization. That keeps Hyperion's "no third-party dependencies"
 * promise intact even with a full UI attached.
 */
public final class CacheServer {

    private static final int DEFAULT_PORT = 7373;

    private final AtomicReference<HyperionCache<String, String>> cache;
    private final long initialMaximumSize;
    private final long initialTtlMs;
    private final HttpServer server;
    private final DemoEngine demo = new DemoEngine();
    private final int port;

    public CacheServer(int port, long maximumSize, long defaultTtlMs) throws IOException {
        this.port = port;
        this.initialMaximumSize = maximumSize;
        this.initialTtlMs = defaultTtlMs;
        this.cache = new AtomicReference<>(buildCache(maximumSize, defaultTtlMs));
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
        registerRoutes();
    }

    private static HyperionCache<String, String> buildCache(long maximumSize, long ttlMs) {
        HyperionCache.Builder<String, String> b = HyperionCache.<String, String>builder()
                .maximumSize(Math.max(1, maximumSize));
        if (ttlMs > 0) {
            b.expireAfterWrite(Duration.ofMillis(ttlMs));
        }
        return b.build();
    }

    /** Applies a one-off action against the live cache; used for startup seeding. */
    public void seed(java.util.function.Consumer<HyperionCache<String, String>> action) {
        action.accept(cache.get());
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return port;
    }

    public String url() {
        return "http://localhost:" + port + "/";
    }

    // --------------------------------------------------------------- routing

    private void registerRoutes() {
        server.createContext("/", this::handleStatic);
        server.createContext("/api/state", ex -> json(ex, stateJson()));
        server.createContext("/api/put", this::handlePut);
        server.createContext("/api/get", this::handleGet);
        server.createContext("/api/invalidate", this::handleInvalidate);
        server.createContext("/api/clear", this::handleClear);
        server.createContext("/api/cleanup", ex -> {
            cache.get().cleanUp();
            json(ex, stateJson());
        });
        server.createContext("/api/config", this::handleConfig);
        server.createContext("/api/simulate", this::handleSimulate);
    }

    // --------------------------------------------------------------- handlers

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        // Only allow simple, safe names — never path traversal.
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (!name.matches("[A-Za-z0-9._-]+")) {
            send(ex, 400, "text/plain", "bad path");
            return;
        }
        String resource = "/ui/" + name;
        try (InputStream in = CacheServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                send(ex, 404, "text/plain", "not found: " + name);
                return;
            }
            byte[] body = in.readAllBytes();
            send(ex, 200, contentType(name), body);
        }
    }

    private void handlePut(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST required");
            return;
        }
        Map<String, String> body = Json.parseFlatObject(readBody(ex));
        String key = body.get("key");
        String value = body.getOrDefault("value", "");
        if (key == null || key.isBlank()) {
            error(ex, 400, "key is required");
            return;
        }
        long ttlMs = parseLong(body.get("ttlMs"), 0L);
        try {
            if (ttlMs > 0) {
                cache.get().put(key, value, Duration.ofMillis(ttlMs));
            } else {
                cache.get().put(key, value);
            }
        } catch (RuntimeException e) {
            error(ex, 400, e.getMessage());
            return;
        }
        json(ex, stateJson());
    }

    private void handleGet(HttpExchange ex) throws IOException {
        String key = queryParam(ex, "key");
        if (key == null || key.isBlank()) {
            error(ex, 400, "key is required");
            return;
        }
        String value = cache.get().getIfPresent(key);
        String payload = Json.obj()
                .field("found", value != null)
                .field("key", key)
                .field("value", value)
                .rawField("state", stateJson())
                .end()
                .done();
        json(ex, payload);
    }

    private void handleInvalidate(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST required");
            return;
        }
        Map<String, String> body = Json.parseFlatObject(readBody(ex));
        String key = body.get("key");
        if (key == null || key.isBlank()) {
            error(ex, 400, "key is required");
            return;
        }
        cache.get().invalidate(key);
        json(ex, stateJson());
    }

    private void handleClear(HttpExchange ex) throws IOException {
        cache.get().invalidateAll();
        json(ex, stateJson());
    }

    private void handleConfig(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST required");
            return;
        }
        Map<String, String> body = Json.parseFlatObject(readBody(ex));
        long size = parseLong(body.get("maximumSize"), initialMaximumSize);
        long ttlMs = parseLong(body.get("defaultTtlMs"), initialTtlMs);
        cache.set(buildCache(Math.max(1, size), Math.max(0, ttlMs)));
        json(ex, stateJson());
    }

    private void handleSimulate(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST required");
            return;
        }
        Map<String, String> body = Json.parseFlatObject(readBody(ex));
        String scenario = body.getOrDefault("scenario", "scan");
        int capacity = (int) Math.min(100_000, Math.max(10, parseLong(body.get("capacity"), 100)));
        json(ex, demo.run(scenario, capacity));
    }

    // --------------------------------------------------------------- payloads

    private String stateJson() {
        HyperionCache<String, String> c = cache.get();
        CacheStats s = c.stats();
        CacheDiagnostics d = c.diagnostics();
        List<CacheEntryView> entries = c.snapshot();

        Json.Writer stats = Json.obj()
                .field("hitCount", s.hitCount())
                .field("missCount", s.missCount())
                .field("requestCount", s.requestCount())
                .field("hitRate", s.hitRate())
                .field("missRate", s.missRate())
                .field("loadSuccessCount", s.loadSuccessCount())
                .field("loadFailureCount", s.loadFailureCount())
                .field("evictionCount", s.evictionCount())
                .field("expirationCount", s.expirationCount())
                .field("size", s.size())
                .end();

        Json.Writer diag = Json.obj()
                .field("estimatedSize", d.estimatedSize())
                .field("estimatedWeight", d.estimatedWeight())
                .field("maximumWeight", d.maximumWeight())
                .field("windowSize", d.windowSize())
                .field("probationSize", d.probationSize())
                .field("protectedSize", d.protectedSize())
                .field("windowWeight", d.windowWeight())
                .field("probationWeight", d.probationWeight())
                .field("protectedWeight", d.protectedWeight())
                .field("scheduledTimers", d.scheduledTimers())
                .end();

        StringBuilder arr = new StringBuilder("[");
        boolean first = true;
        for (CacheEntryView e : entries) {
            if (!first) {
                arr.append(',');
            }
            first = false;
            arr.append(Json.obj()
                    .field("key", e.key())
                    .field("value", e.value())
                    .field("segment", e.segment())
                    .field("weight", e.weight())
                    .field("ttlRemainingMs", e.ttlRemainingMs())
                    .end().done());
        }
        arr.append(']');

        return Json.obj()
                .rawField("stats", stats.done())
                .rawField("diagnostics", diag.done())
                .rawField("entries", arr.toString())
                .end()
                .done();
    }

    // --------------------------------------------------------------- helpers

    private static String contentType(String name) {
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String queryParam(HttpExchange ex, String name) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void json(HttpExchange ex, String body) throws IOException {
        send(ex, 200, "application/json; charset=utf-8", body);
    }

    private static void error(HttpExchange ex, int status, String message) throws IOException {
        send(ex, status, "application/json; charset=utf-8",
                Json.obj().field("error", message).end().done());
    }

    private static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
        send(ex, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    public static int defaultPort() {
        return DEFAULT_PORT;
    }
}
