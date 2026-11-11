package io.github.ainouss.kitchensink.local;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The characterization cases: the legacy's behaviour, recorded as requests and responses in the JSON files of
 * {@code characterization/cases/}. Played by the characterization tests and by {@code ks rules}.
 * The format is strict, so a typo fails the load instead of passing silently. {@code {{run}}} is replaced by a token
 * unique to each run, so registered emails never collide.
 */
public final class Cases {

    public static final String RUN_PLACEHOLDER = "{{run}}";

    private static final Pattern RULES = Pattern.compile("R\\d+( R\\d+)*");
    private static final Set<String> FILE_KEYS = Set.of("description", "cases");
    private static final Set<String> CASE_KEYS = Set.of("id", "rule", "title", "note", "steps");
    private static final Set<String> STEP_KEYS = Set.of("request", "expect");
    private static final Set<String> REQUEST_KEYS = Set.of("method", "path", "headers", "json", "body");
    private static final Set<String> EXPECT_KEYS = Set.of("status", "body", "json", "keys");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Cases() {
    }

    /** All cases of the {@code *.json} files in {@code directory}, in file name order, with a fresh run token. */
    public static List<Case> load(Path directory) {
        return load(directory, runToken());
    }

    public static List<Case> load(Path directory, String run) {
        List<Path> files;
        try (var list = Files.list(directory)) {
            files = list.filter(file -> file.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the characterization cases in " + directory, e);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("No characterization case file in " + directory);
        }
        List<Case> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Path file : files) {
            for (Case c : parse(file.getFileName().toString(), read(file).replace(RUN_PLACEHOLDER, run))) {
                if (!ids.add(c.id())) {
                    throw new IllegalStateException("Duplicate case id " + c.id() + " in " + c.file());
                }
                cases.add(c);
            }
        }
        return List.copyOf(cases);
    }

    public static List<Case> parse(String file, String text) {
        JsonNode root = JSON.readTree(text);
        String where = file;
        only(root, FILE_KEYS, where);
        JsonNode list = root.get("cases");
        if (list == null || !list.isArray() || list.isEmpty()) {
            throw invalid(where, "'cases' must be a non-empty array");
        }
        List<Case> cases = new ArrayList<>();
        for (JsonNode node : list) {
            String id = text(node, "id", file);
            where = file + " case " + id;
            only(node, CASE_KEYS, where);
            String rule = text(node, "rule", where);
            if (!RULES.matcher(rule).matches()) {
                throw invalid(where, "'rule' must name assessment rules, as 'R4' or 'R9 R11', not '" + rule + "'");
            }
            JsonNode steps = node.get("steps");
            if (steps == null || !steps.isArray() || steps.isEmpty()) {
                throw invalid(where, "'steps' must be a non-empty array");
            }
            List<Step> parsed = new ArrayList<>();
            int index = 0;
            for (JsonNode step : steps) {
                parsed.add(step(step, where + " step " + (++index)));
            }
            cases.add(new Case(file, id, rule, text(node, "title", where), List.copyOf(parsed)));
        }
        return cases;
    }

    private static Step step(JsonNode node, String where) {
        only(node, STEP_KEYS, where);
        JsonNode request = node.get("request");
        JsonNode expect = node.get("expect");
        if (request == null || !request.isObject()) {
            throw invalid(where, "'request' must be an object");
        }
        if (expect == null || !expect.isObject() || expect.isEmpty()) {
            throw invalid(where, "'expect' must be an object with at least one expectation");
        }
        only(request, REQUEST_KEYS, where + " request");
        only(expect, EXPECT_KEYS, where + " expect");

        String method = text(request, "method", where);
        if (!METHODS.contains(method)) {
            throw invalid(where, "unknown method " + method);
        }
        String path = text(request, "path", where);
        if (!path.startsWith("/")) {
            throw invalid(where, "'path' must start with '/', relative to the target's base URL");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode h = request.get("headers");
        if (h != null) {
            if (!h.isObject()) {
                throw invalid(where, "'headers' must be an object");
            }
            for (Map.Entry<String, JsonNode> header : h.properties()) {
                if (!header.getValue().isString()) {
                    throw invalid(where, "header " + header.getKey() + " must be a string");
                }
                headers.put(header.getKey(), header.getValue().stringValue());
            }
        }
        JsonNode json = request.get("json");
        JsonNode rawBody = request.get("body");
        if (json != null && rawBody != null) {
            throw invalid(where, "a request has either 'json' or 'body', not both");
        }
        String body = json != null ? JSON.writeValueAsString(json) : rawBody != null ? rawBody.stringValue() : null;

        Integer status = null;
        JsonNode s = expect.get("status");
        if (s != null) {
            if (!s.isInt() || s.intValue() < 100 || s.intValue() > 599) {
                throw invalid(where, "'status' must be an HTTP status code");
            }
            status = s.intValue();
        }
        JsonNode b = expect.get("body");
        if (b != null && !b.isString()) {
            throw invalid(where, "expected 'body' is the exact text, a string ('' for an empty body)");
        }
        JsonNode j = expect.get("json");
        if (j != null && (!j.isObject() || j.isEmpty())) {
            throw invalid(where, "expected 'json' must be a non-empty object");
        }
        Set<String> keys = null;
        JsonNode k = expect.get("keys");
        if (k != null) {
            if (!k.isArray() || k.isEmpty()) {
                throw invalid(where, "expected 'keys' must be a non-empty array of names");
            }
            keys = new LinkedHashSet<>();
            for (JsonNode key : k) {
                keys.add(key.stringValue());
            }
        }
        return new Step(new Request(method, path, Map.copyOf(headers), body),
                new Expect(status, b == null ? null : b.stringValue(), j, keys == null ? null : Set.copyOf(keys)));
    }

    private static void only(JsonNode node, Set<String> allowed, String where) {
        if (!node.isObject()) {
            throw invalid(where, "must be an object");
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw invalid(where, "unknown key '" + entry.getKey() + "' (allowed: " + allowed + ")");
            }
        }
    }

    private static String text(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw invalid(where, "'" + field + "' must be a non-blank string");
        }
        return value.stringValue();
    }

    private static IllegalStateException invalid(String where, String why) {
        return new IllegalStateException("Invalid characterization case file: " + where + ": " + why);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Lower-case letters and digits only: valid in an email, and never looks like a name (rule R2). */
    public static String runToken() {
        var random = new SecureRandom();
        return Long.toString(System.currentTimeMillis(), 36) + Integer.toString(1296 + random.nextInt(45360), 36);
    }

    /** One case: the requests that show one of the legacy's rules. */
    public record Case(String file, String id, String rule, String title, List<Step> steps) {

        public String displayName() {
            return "[" + rule + "] " + id + ": " + title;
        }
    }

    public record Step(Request request, Expect expect) {
    }

    /** A request; {@code body} is the exact text sent, or null. */
    public record Request(String method, String path, Map<String, String> headers, String body) {
    }

    /**
     * What the legacy answered. {@code json} must match on every key it gives; {@code keys} is the exact key set.
     * A null field is not checked.
     */
    public record Expect(Integer status, String body, JsonNode json, Set<String> keys) {
    }
}
