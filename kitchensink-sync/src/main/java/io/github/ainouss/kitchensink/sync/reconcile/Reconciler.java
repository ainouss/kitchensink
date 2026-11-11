package io.github.ainouss.kitchensink.sync.reconcile;

import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.Sorts.ascending;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.COLLECTION;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.EMAIL;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.ID;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.NAME;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.PHONE_NUMBER;

import java.security.MessageDigest;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import io.github.ainouss.kitchensink.sync.SyncProperties;
import io.github.ainouss.kitchensink.sync.forward.MongoMemberSink;
import io.github.ainouss.kitchensink.sync.member.MemberRow;
import io.github.ainouss.kitchensink.sync.member.MemberRowMapper;
import io.github.ainouss.kitchensink.sync.reconcile.ReconciliationReport.Cause;
import io.github.ainouss.kitchensink.sync.reconcile.ReconciliationReport.Difference;

/**
 * Compares PostgreSQL and MongoDB record by record. Both sides are read in id order and compared by hash, so memory
 * stays flat. A difference may just be stream lag, so it is re-checked after a short settle time. Quarantined members
 * are counted but do not fail the run. One run at a time.
 */
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);
    private static final int FETCH = 10_000;
    private static final int CHUNK = 1_000;

    private final SyncProperties.Legacy legacy;
    private final SyncProperties.Reconcile settings;
    private final MongoTemplate mongo;
    private final MongoCollection<Document> members;
    private final MemberRowMapper mapper;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private Runnable beforeSecondLook = () -> { };

    public Reconciler(SyncProperties properties, MongoTemplate mongo, MemberRowMapper mapper, Clock clock) {
        this.legacy = properties.legacy();
        this.settings = properties.reconcile();
        this.mongo = mongo;
        this.members = mongo.getDb().getCollection(COLLECTION);
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Thrown when a run is requested while another one is running. */
    public static class AlreadyRunningException extends RuntimeException {
        AlreadyRunningException() {
            super("A reconciliation is already running");
        }
    }

    public ReconciliationReport latest() {
        return mongo.findOne(new Query().with(Sort.by(Sort.Direction.DESC, "startedAt")).limit(1),
                ReconciliationReport.class);
    }

    public ReconciliationReport reconcile() {
        if (!running.compareAndSet(false, true)) {
            throw new AlreadyRunningException();
        }
        try {
            ReconciliationReport report = run();
            ReconciliationReport saved = mongo.insert(report);
            log.info("Reconciliation {}: {} legacy / {} MongoDB, {}, {} in flight, in {} ms",
                    report.green() ? "GREEN" : "RED", report.legacyCount(), report.mongoCount(), report.counts(),
                    report.inFlight(), report.durationMillis());
            return saved;
        } catch (SQLException e) {
            throw new IllegalStateException("The legacy database could not be read: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while letting differences settle", e);
        } finally {
            running.set(false);
        }
    }

    private ReconciliationReport run() throws SQLException, InterruptedException {
        Instant started = clock.instant();
        Map<Cause, Long> firstPass = new EnumMap<>(Cause.class);
        Map<Long, Cause> toRecheck = new LinkedHashMap<>();
        List<Difference> unchecked = new ArrayList<>();
        MessageDigest legacyDigest = CanonicalRecord.digest();
        MessageDigest mongoDigest = CanonicalRecord.digest();
        String readLsn;
        long legacyCount;
        long mongoCount;

        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                // The first query fixes the snapshot, so this is the log position of the whole read.
                readLsn = single(statement, "SELECT pg_current_wal_lsn()::text");
                legacyCount = Long.parseLong(single(statement, "SELECT count(*) FROM member"));
            }
            mongoCount = members.countDocuments();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, name, email, phone_number FROM member ORDER BY id")) {
                select.setFetchSize(FETCH);
                try (ResultSet rows = select.executeQuery();
                     MongoCursor<Document> documents = members.find()
                             .projection(include(NAME, EMAIL, PHONE_NUMBER)).sort(ascending(ID))
                             .batchSize(FETCH).cursor()) {
                    MemberRow row = next(rows);
                    MemberRow doc = next(documents);
                    while (row != null || doc != null) {
                        if (doc == null || (row != null && row.id() < doc.id())) {
                            legacyDigest.update(CanonicalRecord.sha256(row));
                            found(Cause.MISSING_IN_MONGODB, row.id(), List.of(), firstPass, toRecheck, unchecked);
                            row = next(rows);
                        } else if (row == null || doc.id() < row.id()) {
                            mongoDigest.update(CanonicalRecord.sha256(doc));
                            found(Cause.EXTRA_IN_MONGODB, doc.id(), List.of(), firstPass, toRecheck, unchecked);
                            doc = next(documents);
                        } else {
                            byte[] legacyHash = CanonicalRecord.sha256(row);
                            byte[] mongoHash = CanonicalRecord.sha256(doc);
                            legacyDigest.update(legacyHash);
                            mongoDigest.update(mongoHash);
                            if (!CanonicalRecord.same(legacyHash, mongoHash)) {
                                found(Cause.DIFFERENT, row.id(), CanonicalRecord.differences(row, doc), firstPass,
                                        toRecheck, unchecked);
                            }
                            row = next(rows);
                            doc = next(documents);
                        }
                    }
                }
            }
            connection.commit();
        }

        // Quarantined members are counted separately, not as differences.
        Set<Object> quarantined = quarantineIds();
        long quarantinedCount = 0;
        for (var it = toRecheck.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (quarantined.contains(entry.getKey())) {
                firstPass.merge(entry.getValue(), -1L, Long::sum);
                quarantinedCount++;
                it.remove();
            }
        }

        // Re-check each difference after the stream has had time to catch up.
        Map<Cause, Long> counts = new EnumMap<>(firstPass);
        List<Difference> confirmed = new ArrayList<>();
        long inFlight = 0;
        if (!toRecheck.isEmpty()) {
            Thread.sleep(settings.settle().toMillis());
            beforeSecondLook.run();
            Map<Long, Difference> now = secondLook(toRecheck.keySet());
            for (var entry : toRecheck.entrySet()) {
                counts.merge(entry.getValue(), -1L, Long::sum);
                Difference still = now.get(entry.getKey());
                if (still == null) {
                    inFlight++;
                } else {
                    counts.merge(still.cause(), 1L, Long::sum);
                    confirmed.add(still);
                }
            }
        }
        counts.put(Cause.QUARANTINED, quarantinedCount);
        for (Cause cause : Cause.values()) {
            counts.putIfAbsent(cause, 0L); // show every cause, even at zero
        }
        counts.replaceAll((cause, n) -> Math.max(n, 0));

        Instant finished = clock.instant();
        boolean green = isGreen(counts);
        return new ReconciliationReport(null, started, finished, Duration.between(started, finished).toMillis(),
                readLsn, legacyCount, mongoCount, hex(legacyDigest), hex(mongoDigest), counts, inFlight,
                samples(confirmed, unchecked), green);
    }

    private static boolean isGreen(Map<Cause, Long> counts) {
        return counts.getOrDefault(Cause.MISSING_IN_MONGODB, 0L) == 0
                && counts.getOrDefault(Cause.EXTRA_IN_MONGODB, 0L) == 0
                && counts.getOrDefault(Cause.DIFFERENT, 0L) == 0;
    }

    private void found(Cause cause, long id, List<CanonicalRecord.FieldDifference> fields, Map<Cause, Long> counts,
                       Map<Long, Cause> toRecheck, List<Difference> unchecked) {
        counts.merge(cause, 1L, Long::sum);
        if (toRecheck.size() < settings.recheckMax()) {
            toRecheck.put(id, cause);
        } else if (unchecked.size() < settings.samples() * 3) {
            unchecked.add(new Difference(cause, id, fields));
        }
    }

    /** Reads these ids again on both sides and returns the differences that remain. */
    private Map<Long, Difference> secondLook(Set<Long> ids) throws SQLException {
        List<Long> all = new ArrayList<>(ids);
        Map<Long, MemberRow> legacyRows = new HashMap<>();
        Map<Long, MemberRow> mongoRows = new HashMap<>();
        try (Connection connection = connect();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT id, name, email, phone_number FROM member WHERE id = ANY (?)")) {
            for (int from = 0; from < all.size(); from += CHUNK) {
                List<Long> chunk = all.subList(from, Math.min(from + CHUNK, all.size()));
                Array array = connection.createArrayOf("bigint", chunk.toArray());
                select.setArray(1, array);
                try (ResultSet rows = select.executeQuery()) {
                    for (MemberRow row = next(rows); row != null; row = next(rows)) {
                        legacyRows.put(row.id(), row);
                    }
                }
                members.find(in(ID, chunk)).projection(include(NAME, EMAIL, PHONE_NUMBER))
                        .forEach(document -> {
                            MemberRow row = mapper.toRow(document);
                            mongoRows.put(row.id(), row);
                        });
            }
        }
        Map<Long, Difference> still = new HashMap<>();
        for (Long id : all) {
            MemberRow row = legacyRows.get(id);
            MemberRow doc = mongoRows.get(id);
            if (row == null && doc == null) {
                continue;
            }
            if (doc == null) {
                still.put(id, new Difference(Cause.MISSING_IN_MONGODB, id, List.of()));
            } else if (row == null) {
                still.put(id, new Difference(Cause.EXTRA_IN_MONGODB, id, List.of()));
            } else if (!CanonicalRecord.same(CanonicalRecord.sha256(row), CanonicalRecord.sha256(doc))) {
                still.put(id, new Difference(Cause.DIFFERENT, id, CanonicalRecord.differences(row, doc)));
            }
        }
        return still;
    }

    private List<Difference> samples(List<Difference> confirmed, List<Difference> unchecked) {
        Map<Cause, Integer> taken = new EnumMap<>(Cause.class);
        List<Difference> samples = new ArrayList<>();
        for (List<Difference> list : List.of(confirmed, unchecked)) {
            for (Difference difference : list) {
                if (taken.merge(difference.cause(), 1, Integer::sum) <= settings.samples()) {
                    samples.add(difference);
                }
            }
        }
        return samples;
    }

    private Set<Object> quarantineIds() {
        Set<Object> ids = new HashSet<>();
        mongo.getDb().getCollection(MongoMemberSink.QUARANTINE).find().projection(include(ID))
                .forEach(document -> ids.add(document.get(ID)));
        return ids;
    }

    private MemberRow next(MongoCursor<Document> documents) {
        return documents.hasNext() ? mapper.toRow(documents.next()) : null;
    }

    private static MemberRow next(ResultSet rows) throws SQLException {
        if (!rows.next()) {
            return null;
        }
        return new MemberRow(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4));
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(legacy.jdbcUrl(), legacy.user(), legacy.password());
    }

    private static String single(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    /** For tests: an action to run just before the re-check. */
    void beforeSecondLook(Runnable action) {
        this.beforeSecondLook = action;
    }
}
