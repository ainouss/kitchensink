package io.github.ainouss.kitchensink.facade;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** A fake upstream for tests: returns canned answers per method and path, and records every request. */
public final class StubUpstream implements AutoCloseable {

    public record Answer(int status, String contentType, String body, Duration delay, Map<String, String> headers) {

        public static Answer json(int status, String body) {
            return new Answer(status, "application/json", body, Duration.ZERO, Map.of());
        }

        public Answer after(Duration delay) {
            return new Answer(status, contentType, body, delay, headers);
        }

        public Answer header(String name, String value) {
            Map<String, String> more = new java.util.HashMap<>(headers);
            more.put(name, value);
            return new Answer(status, contentType, body, delay, Map.copyOf(more));
        }
    }

    public record Received(String method, String pathAndQuery, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    private final HttpServer server;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();

    public StubUpstream() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** What {@code method path} (the path without its query) answers from now on. */
    public StubUpstream answer(String method, String path, Answer answer) {
        answers.put(method + " " + path, answer);
        return this;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public void reset() {
        answers.clear();
        received.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        received.add(new Received(exchange.getRequestMethod(), path + (query == null ? "" : "?" + query),
                Map.copyOf(exchange.getRequestHeaders()),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Answer answer = answers.getOrDefault(exchange.getRequestMethod() + " " + path,
                new Answer(404, null, "", Duration.ZERO, Map.of()));
        try {
            Thread.sleep(answer.delay().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (answer.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", answer.contentType());
        }
        answer.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
