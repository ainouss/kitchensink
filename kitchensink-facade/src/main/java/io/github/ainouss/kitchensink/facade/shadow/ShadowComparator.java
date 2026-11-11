package io.github.ainouss.kitchensink.facade.shadow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compares both sides' answers to the same read: the status, then the body as JSON (key order ignored) or as bytes.
 * Differences are listed by JSON path, then checked against the accepted ones in {@link Normalisation}.
 */
@Component
public class ShadowComparator {

    static final int MAX_DIFFS = 20;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Normalisation normalisation;

    public ShadowComparator(Normalisation normalisation) {
        this.normalisation = normalisation;
    }

    public enum Verdict { MATCH, MISMATCH, ACCEPTED }

    /** A difference at a JSON path ({@code $} for the whole body, {@code status} for the status). */
    public record Diff(String path, String legacy, String newer) {
    }

    public record Outcome(Verdict verdict, String acceptedBy, List<Diff> diffs) {
    }

    public Outcome compare(String method, String path, SideResponse legacy, SideResponse newer) {
        List<Diff> diffs = new ArrayList<>();
        if (legacy.status() != newer.status()) {
            diffs.add(new Diff("status", Integer.toString(legacy.status()), Integer.toString(newer.status())));
        } else if (legacy.isJson() && newer.isJson() && legacy.body().length > 0 && newer.body().length > 0) {
            try {
                compare("$", JSON.readTree(legacy.body()), JSON.readTree(newer.body()), diffs);
            } catch (JacksonException e) {
                bytes(legacy, newer, diffs);
            }
        } else {
            bytes(legacy, newer, diffs);
        }
        if (diffs.isEmpty()) {
            return new Outcome(Verdict.MATCH, null, List.of());
        }
        String rule = normalisation.acceptedBy(method, path, legacy, newer);
        return new Outcome(rule == null ? Verdict.MISMATCH : Verdict.ACCEPTED, rule, List.copyOf(diffs));
    }

    private static void bytes(SideResponse legacy, SideResponse newer, List<Diff> diffs) {
        if (!Arrays.equals(legacy.body(), newer.body())) {
            diffs.add(new Diff("$", legacy.text(200), newer.text(200)));
        }
    }

    private static void compare(String path, JsonNode a, JsonNode b, List<Diff> diffs) {
        if (diffs.size() >= MAX_DIFFS) {
            return;
        }
        if (a.isNumber() && b.isNumber()) {
            if (a.decimalValue().compareTo(b.decimalValue()) != 0) {
                diffs.add(new Diff(path, a.toString(), b.toString()));
            }
        } else if (a.isObject() && b.isObject()) {
            TreeSet<String> names = new TreeSet<>();
            a.properties().forEach(e -> names.add(e.getKey()));
            b.properties().forEach(e -> names.add(e.getKey()));
            for (String name : names) {
                JsonNode x = a.get(name);
                JsonNode y = b.get(name);
                if (x == null || y == null) {
                    diffs.add(new Diff(path + "." + name, x == null ? "(absent)" : x.toString(),
                            y == null ? "(absent)" : y.toString()));
                } else {
                    compare(path + "." + name, x, y, diffs);
                }
                if (diffs.size() >= MAX_DIFFS) {
                    return;
                }
            }
        } else if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                diffs.add(new Diff(path + ".length", Integer.toString(a.size()), Integer.toString(b.size())));
            }
            Iterator<JsonNode> x = a.iterator();
            Iterator<JsonNode> y = b.iterator();
            for (int i = 0; x.hasNext() && y.hasNext() && diffs.size() < MAX_DIFFS; i++) {
                compare(path + "[" + i + "]", x.next(), y.next(), diffs);
            }
        } else if (!a.equals(b)) {
            diffs.add(new Diff(path, a.toString(), b.toString()));
        }
    }

    /** The diffs in the form stored with a mismatch. */
    static List<Map<String, String>> asMaps(List<Diff> diffs) {
        return diffs.stream().map(d -> Map.of("path", d.path(), "legacy", d.legacy(), "new", d.newer())).toList();
    }
}
