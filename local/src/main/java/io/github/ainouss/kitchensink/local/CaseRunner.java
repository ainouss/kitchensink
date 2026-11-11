package io.github.ainouss.kitchensink.local;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import io.github.ainouss.kitchensink.local.Cases.Case;
import io.github.ainouss.kitchensink.local.Cases.Expect;
import io.github.ainouss.kitchensink.local.Cases.Request;
import io.github.ainouss.kitchensink.local.Cases.Step;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plays cases over HTTP against one base URL, whichever system it is. Steps run in order; the first step that differs
 * fails the case with the request, the differences and the body received.
 */
public final class CaseRunner {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int SHOWN_BODY = 2000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String base;
    private final HttpClient http;

    public CaseRunner(URI target) {
        String url = target.toString();
        this.base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public String base() {
        return base;
    }

    /** Plays a case; fails like an assertion, with what differs. */
    public void play(Case c) throws IOException, InterruptedException {
        Optional<String> difference = check(c);
        if (difference.isPresent()) {
            throw new AssertionError(difference.get());
        }
    }

    /** Plays a case: empty if every step answered as the legacy did, else the first step that differs. */
    public Optional<String> check(Case c) throws IOException, InterruptedException {
        int index = 0;
        for (Step step : c.steps()) {
            index++;
            HttpResponse<String> response = send(step.request());
            List<String> differences = differences(step.expect(), response);
            if (!differences.isEmpty()) {
                return Optional.of(c.id() + " [" + c.rule() + "], step " + index + " of " + c.steps().size()
                        + ": " + step.request().method() + " " + base + step.request().path()
                        + (step.request().body() == null ? "" : " " + step.request().body())
                        + "\n  " + String.join("\n  ", differences)
                        + "\n  received " + response.statusCode() + ": " + shown(response.body()));
            }
        }
        return Optional.empty();
    }

    private HttpResponse<String> send(Request request) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + request.path()))
                .timeout(TIMEOUT)
                .header("Accept", "application/json");
        if (request.body() != null && !hasHeader(request.headers(), "Content-Type")) {
            builder.header("Content-Type", "application/json");
        }
        request.headers().forEach(builder::setHeader);
        builder.method(request.method(), request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(request.body()));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static List<String> differences(Expect expect, HttpResponse<String> response) {
        List<String> differences = new ArrayList<>();
        if (expect.status() != null && expect.status() != response.statusCode()) {
            differences.add("status: expected " + expect.status() + ", got " + response.statusCode());
        }
        String body = response.body() == null ? "" : response.body();
        if (expect.body() != null && !expect.body().equals(body)) {
            differences.add(expect.body().isEmpty()
                    ? "body: expected none"
                    : "body: expected exactly " + expect.body());
        }
        if (expect.json() == null && expect.keys() == null) {
            return differences;
        }
        JsonNode actual;
        try {
            actual = JSON.readTree(body);
        } catch (JacksonException e) {
            differences.add("body: expected a JSON object, got text that is not JSON");
            return differences;
        }
        if (actual == null || !actual.isObject()) {
            differences.add("body: expected a JSON object");
            return differences;
        }
        if (expect.json() != null) {
            for (Map.Entry<String, JsonNode> field : expect.json().properties()) {
                JsonNode value = actual.get(field.getKey());
                if (value == null) {
                    differences.add("json: expected '" + field.getKey() + "', absent");
                } else if (!same(field.getValue(), value)) {
                    differences.add("json: expected '" + field.getKey() + "' = " + field.getValue() + ", got " + value);
                }
            }
        }
        if (expect.keys() != null) {
            Set<String> names = new TreeSet<>();
            actual.properties().forEach(field -> names.add(field.getKey()));
            if (!names.equals(new TreeSet<>(expect.keys()))) {
                differences.add("keys: expected exactly " + new TreeSet<>(expect.keys()) + ", got " + names);
            }
        }
        return differences;
    }

    /** JSON equality, with numbers compared by value. */
    private static boolean same(JsonNode expected, JsonNode actual) {
        if (expected.isNumber() && actual.isNumber()) {
            return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
        }
        return expected.equals(actual);
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(name::equalsIgnoreCase);
    }

    private static String shown(String body) {
        if (body == null || body.isEmpty()) {
            return "(no body)";
        }
        return body.length() <= SHOWN_BODY ? body : body.substring(0, SHOWN_BODY) + "… (" + body.length() + " chars)";
    }
}
