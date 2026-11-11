package io.github.ainouss.kitchensink.sync.reconcile;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The result of one reconciliation between the legacy table and the {@code members} collection, stored in
 * {@code reconciliations}.
 *
 * <p>Green means no member is missing, extra or different after re-checking. Quarantined members are listed but do
 * not affect the verdict.
 *
 * @param legacyReadLsn the PostgreSQL log position of the snapshot that was read
 * @param counts        the differences by cause, after the re-check
 * @param inFlight      differences that were gone at the re-check
 * @param samples       the first differences of each cause, field by field
 */
@Document("reconciliations")
public record ReconciliationReport(
        @Id String id,
        Instant startedAt,
        Instant finishedAt,
        long durationMillis,
        String legacyReadLsn,
        long legacyCount,
        long mongoCount,
        String legacyDigest,
        String mongoDigest,
        Map<Cause, Long> counts,
        long inFlight,
        List<Difference> samples,
        boolean green) {

    public enum Cause { MISSING_IN_MONGODB, EXTRA_IN_MONGODB, DIFFERENT, QUARANTINED }

    /** One member that differs; for {@link Cause#DIFFERENT}, the fields with both values. */
    public record Difference(Cause cause, long id, List<CanonicalRecord.FieldDifference> fields) {
    }

    public long count(Cause cause) {
        return counts.getOrDefault(cause, 0L);
    }

    /** Both databases hold exactly the same records: same counts and same digests. */
    public boolean identical() {
        return legacyCount == mongoCount && legacyDigest.equals(mongoDigest);
    }
}
