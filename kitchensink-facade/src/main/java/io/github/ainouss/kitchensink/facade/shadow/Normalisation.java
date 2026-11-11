package io.github.ainouss.kitchensink.facade.shadow;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The accepted differences between the legacy and the new application, from {@code normalisation.json} (shared
 * with the characterization suite). A compare that matches one is counted as accepted, not as a mismatch.
 */
@Component
public class Normalisation {

    public static final String RESOURCE = "/kitchensink/normalisation.json";

    private final List<Accepted> accepted;

    public Normalisation() {
        this(load());
    }

    Normalisation(JsonNode document) {
        List<Accepted> list = new ArrayList<>();
        for (JsonNode entry : document.get("acceptedDifferences")) {
            JsonNode match = entry.get("match");
            if (match == null) {
                continue; // a read never shows this difference
            }
            list.add(new Accepted(entry.get("rule").stringValue(),
                    match.has("method") ? match.get("method").stringValue() : null,
                    Pattern.compile(match.get("path").stringValue()),
                    match.has("legacyStatus") ? match.get("legacyStatus").intValue() : null,
                    match.has("newStatus") ? match.get("newStatus").intValue() : null));
        }
        this.accepted = List.copyOf(list);
    }

    /** One accepted difference a read can show. */
    record Accepted(String rule, String method, Pattern path, Integer legacyStatus, Integer newStatus) {

        boolean matches(String requestMethod, String requestPath, SideResponse legacy, SideResponse newer) {
            return (method == null || method.equalsIgnoreCase(requestMethod))
                    && path.matcher(requestPath).matches()
                    && (legacyStatus == null || legacyStatus == legacy.status())
                    && (newStatus == null || newStatus == newer.status());
        }
    }

    /** The rule that accepts this difference, or null. */
    public String acceptedBy(String method, String path, SideResponse legacy, SideResponse newer) {
        return accepted.stream().filter(a -> a.matches(method, path, legacy, newer)).map(Accepted::rule)
                .findFirst().orElse(null);
    }

    private static JsonNode load() {
        try (InputStream in = Normalisation.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath (kitchensink-domain)");
            }
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
