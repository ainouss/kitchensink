package io.github.ainouss.kitchensink.facade.shadow;

import java.time.Clock;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import com.mongodb.client.MongoCollection;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import io.github.ainouss.kitchensink.facade.FacadeProperties;

/**
 * Records the dual-run: counts each compare by route and outcome, and saves each mismatch with the request and both
 * answers in {@code dualrun_mismatches} (up to {@code max-stored}).
 */
@Component
public class DualRun {

    public static final String MISMATCHES = "dualrun_mismatches";
    private static final Logger log = LoggerFactory.getLogger(DualRun.class);
    private static final Pattern NUMBER_SEGMENT = Pattern.compile("/\\d+(?=/|$)");
    private static final int STORED_BODY = 4_000;

    public enum Outcome { MATCHED, MISMATCHED, ACCEPTED, SHADOW_FAILED, TOO_LARGE, SKIPPED }

    private final MongoCollection<Document> mismatches;
    private final MeterRegistry meters;
    private final int maxStored;
    private final Clock clock;
    private final Map<String, Map<Outcome, AtomicLong>> byRoute = new ConcurrentHashMap<>();
    private final AtomicLong stored = new AtomicLong();

    public DualRun(MongoTemplate mongo, MeterRegistry meters, FacadeProperties properties) {
        this.mismatches = mongo.getDb().getCollection(MISMATCHES);
        this.meters = meters;
        this.maxStored = properties.shadow().maxStored();
        this.clock = Clock.systemUTC();
    }

    /** Turns {@code /members/42} into {@code /members/{id}}, so counts are per route, not per URL. */
    public static String route(String path) {
        return NUMBER_SEGMENT.matcher(path).replaceAll("/{id}");
    }

    public void count(String path, Outcome outcome) {
        String route = route(path);
        byRoute.computeIfAbsent(route, r -> new ConcurrentHashMap<>())
                .computeIfAbsent(outcome, o -> new AtomicLong()).incrementAndGet();
        meters.counter("facade.shadow", "route", route, "outcome", outcome.name().toLowerCase()).increment();
    }

    public void compared(String correlationId, String method, String pathAndQuery, String path, String reads,
                         SideResponse primary, SideResponse shadow, ShadowComparator.Outcome outcome) {
        switch (outcome.verdict()) {
            case MATCH -> count(path, Outcome.MATCHED);
            case ACCEPTED -> count(path, Outcome.ACCEPTED);
            case MISMATCH -> {
                count(path, Outcome.MISMATCHED);
                store(correlationId, method, pathAndQuery, reads, primary, shadow, outcome);
            }
        }
    }

    private void store(String correlationId, String method, String pathAndQuery, String reads, SideResponse primary,
                       SideResponse shadow, ShadowComparator.Outcome outcome) {
        if (stored.get() >= maxStored) {
            return;
        }
        try {
            mismatches.insertOne(new Document("at", Date.from(clock.instant()))
                    .append("correlationId", correlationId)
                    .append("request", new Document("method", method).append("path", pathAndQuery)
                            .append("reads", reads))
                    .append("primary", side(primary))
                    .append("shadow", side(shadow))
                    .append("diff", ShadowComparator.asMaps(outcome.diffs())));
            stored.incrementAndGet();
        } catch (RuntimeException e) {
            log.warn("A dual-run mismatch could not be stored: {}", e.toString());
        }
    }

    private static Document side(SideResponse response) {
        return new Document("side", response.side().name()).append("status", response.status())
                .append("contentType", response.contentType()).append("body", response.text(STORED_BODY));
    }

    /** Counts since this instance started: totals and per route. */
    public Map<String, Object> stats() {
        Map<Outcome, Long> totals = new java.util.EnumMap<>(Outcome.class);
        Map<String, Map<Outcome, Long>> routes = new java.util.TreeMap<>();
        byRoute.forEach((route, outcomes) -> outcomes.forEach((outcome, n) -> {
            totals.merge(outcome, n.get(), Long::sum);
            routes.computeIfAbsent(route, r -> new java.util.EnumMap<>(Outcome.class)).put(outcome, n.get());
        }));
        for (Outcome outcome : Outcome.values()) {
            totals.putIfAbsent(outcome, 0L);
        }
        return Map.of("totals", totals, "routes", routes, "storedMismatches", stored.get());
    }
}
