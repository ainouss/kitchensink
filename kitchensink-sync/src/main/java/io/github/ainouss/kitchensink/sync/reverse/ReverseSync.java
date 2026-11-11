package io.github.ainouss.kitchensink.sync.reverse;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import com.mongodb.client.model.changestream.OperationType;
import org.bson.BsonDocument;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import io.github.ainouss.kitchensink.sync.SyncDirection;
import io.github.ainouss.kitchensink.sync.SyncProperties;
import io.github.ainouss.kitchensink.sync.member.MemberDocumentFields;
import io.github.ainouss.kitchensink.sync.member.MemberRowMapper;

/**
 * The reverse stream: after the cutover, each change to {@code members} is copied back into PostgreSQL, so a
 * rollback loses nothing. It reads a MongoDB change stream and saves its resume token after each batch, so a
 * restart resumes. Only one direction runs at a time, and documents that came from the legacy are never sent back.
 * See docs/migration.md.
 */
public class ReverseSync implements SmartLifecycle {

    public static final String STATE = "sync_state";
    public static final String MARKERS = "sync_markers";
    static final String STATE_ID = "reverse";

    private static final Logger log = LoggerFactory.getLogger(ReverseSync.class);
    private static final int BATCH = 500;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final SyncProperties properties;
    private final MongoDatabase database;
    private final MemberRowMapper mapper;
    private final ReverseSyncStatus status;
    private final BooleanSupplier forwardRunning;
    private final LegacyWriter writer;
    private final MongoCollection<Document> state;
    private final MongoCollection<Document> markers;
    private final SyncDirection direction;
    private final Map<String, CompletableFuture<Void>> drains = new ConcurrentHashMap<>();

    private volatile boolean running;
    private volatile Thread loop;
    private volatile ScheduledExecutorService prober;

    public ReverseSync(SyncProperties properties, MongoDatabase database, MemberRowMapper mapper,
                       ReverseSyncStatus status, BooleanSupplier forwardRunning) {
        this.properties = properties;
        this.database = database;
        this.mapper = mapper;
        this.status = status;
        this.forwardRunning = forwardRunning;
        this.writer = new LegacyWriter(properties.legacy(), database);
        this.state = database.getCollection(STATE).withWriteConcern(WriteConcern.MAJORITY);
        this.markers = database.getCollection(MARKERS).withWriteConcern(WriteConcern.MAJORITY);
        this.direction = new SyncDirection(database, properties);
    }

    /** Starts with the worker if the reverse direction is recorded, or if enabled before any direction is. */
    @Override
    public boolean isAutoStartup() {
        return direction.startsAtStartup(SyncDirection.Direction.REVERSE);
    }

    @Override
    public synchronized void start() {
        start(false);
    }

    /**
     * @param fromNow start from the current position (a new cutover); otherwise resume after the saved token
     */
    public synchronized void start(boolean fromNow) {
        if (running) {
            return;
        }
        if (forwardRunning.getAsBoolean()) {
            throw new IllegalStateException("The forward stream runs: stop it before the reverse stream starts "
                    + "(one direction at a time)");
        }
        if (fromNow) {
            state.deleteOne(eq("_id", STATE_ID));
        }
        Document stored = state.find(eq("_id", STATE_ID)).first();
        BsonDocument token = stored == null ? null : stored.get("token", Document.class)
                .toBsonDocument(Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry());
        var stream = database.watch(List.of(Aggregates.match(Filters.and(
                        in("ns.coll", List.of(MemberDocumentFields.COLLECTION, MARKERS)),
                        in("operationType", List.of("insert", "update", "replace", "delete"))))))
                .fullDocument(FullDocument.UPDATE_LOOKUP)
                .maxAwaitTime(500, TimeUnit.MILLISECONDS)
                .batchSize(BATCH);
        if (token != null) {
            stream = stream.resumeAfter(token);
        }
        // Open the stream before returning, so every later write is captured.
        MongoChangeStreamCursor<ChangeStreamDocument<Document>> cursor = stream.cursor();
        try {
            direction.record(SyncDirection.Direction.REVERSE);
        } catch (RuntimeException e) {
            cursor.close();
            throw e;
        }
        running = true;
        status.running();
        loop = Thread.ofPlatform().name("reverse-sync").start(() -> run(cursor));
        prober = Executors.newSingleThreadScheduledExecutor(task -> Thread.ofVirtual().name("reverse-probe")
                .unstarted(task));
        long every = properties.reverse().probeInterval().toMillis();
        prober.scheduleWithFixedDelay(this::probe, every, every, TimeUnit.MILLISECONDS);
        log.info("Reverse stream started {}", token == null ? "from now" : "after its stored token");
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        prober.shutdownNow();
        try {
            loop.join(Duration.ofSeconds(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        release("stopped");
        status.stopped();
        log.info("Reverse stream stopped; its token is kept for a resume");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Waits until every earlier MongoDB write has reached PostgreSQL, by writing a marker and waiting for it to come
     * through the stream.
     */
    public Duration drain(Duration within) throws TimeoutException, InterruptedException {
        if (!running) {
            throw new IllegalStateException("The reverse stream is not running: nothing to drain");
        }
        long start = System.nanoTime();
        String token = UUID.randomUUID().toString();
        CompletableFuture<Void> applied = new CompletableFuture<>();
        drains.put(token, applied);
        try {
            markers.replaceOne(eq("_id", "drain"), new Document("_id", "drain").append("token", token),
                    new ReplaceOptions().upsert(true));
            applied.get(within.toMillis(), TimeUnit.MILLISECONDS);
            return Duration.ofNanos(System.nanoTime() - start);
        } catch (ExecutionException e) {
            throw new IllegalStateException("The drain did not complete: " + e.getCause().getMessage(), e);
        } finally {
            drains.remove(token);
        }
    }

    private void run(MongoChangeStreamCursor<ChangeStreamDocument<Document>> cursor) {
        try (cursor) {
            while (running) {
                // Wait for one change, then take only what has already arrived,
                // so a busy stream never holds a batch open.
                List<ChangeStreamDocument<Document>> batch = new ArrayList<>();
                ChangeStreamDocument<Document> first = cursor.tryNext();
                if (first != null) {
                    batch.add(first);
                    while (batch.size() < BATCH && cursor.available() > 0) {
                        batch.add(cursor.next());
                    }
                }
                if (!batch.isEmpty()) {
                    apply(batch);
                }
                BsonDocument token = cursor.getResumeToken();
                if (token != null) {
                    state.updateOne(eq("_id", STATE_ID), Updates.combine(Updates.set("token", token),
                            Updates.set("at", new Date())), new UpdateOptions().upsert(true));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            if (running) {
                log.error("Reverse stream failed", e);
                running = false;
                prober.shutdownNow();
                release("failed");
                status.failed(e.toString());
            }
        }
    }

    /** Applies one batch to PostgreSQL, retrying while it is unreachable. */
    private void apply(List<ChangeStreamDocument<Document>> batch) throws InterruptedException {
        List<LegacyWriter.Change> changes = new ArrayList<>(batch.size());
        List<Document> seenMarkers = new ArrayList<>();
        int echoes = 0;
        for (ChangeStreamDocument<Document> event : batch) {
            String collection = event.getNamespace() == null ? null : event.getNamespace().getCollectionName();
            Document document = event.getFullDocument();
            if (MARKERS.equals(collection)) {
                if (document != null) {
                    seenMarkers.add(document);
                }
                continue;
            }
            if (event.getOperationType() == OperationType.DELETE) {
                changes.add(new LegacyWriter.Change.Delete(event.getDocumentKey().getInt64("_id").longValue()));
            } else if (document != null) {
                Document sync = document.get(MemberDocumentFields.SYNC, Document.class);
                if (sync != null && MemberDocumentFields.ORIGIN_LEGACY.equals(
                        sync.getString(MemberDocumentFields.SYNC_ORIGIN))) {
                    echoes++; // came from the legacy: never sent back
                    continue;
                }
                changes.add(new LegacyWriter.Change.Upsert(mapper.toRow(document)));
            } // document already deleted: the delete comes later in the stream
        }
        Duration backoff = Duration.ofMillis(500);
        LegacyWriter.Written written;
        while (true) {
            try {
                written = writer.apply(changes);
                break;
            } catch (SQLException | MongoException e) {
                if (!running) {
                    throw new InterruptedException("stopping while the legacy fails: the batch is replayed on start");
                }
                log.warn("A batch of {} changes did not reach the legacy, retried in {} ms: {}", changes.size(),
                        backoff.toMillis(), e.toString());
                Thread.sleep(backoff.toMillis());
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
        }
        status.batch(written, echoes);
        long now = System.currentTimeMillis();
        for (Document marker : seenMarkers) {
            if ("probe".equals(marker.get("_id")) && marker.get("at") instanceof Number at) {
                status.probeSeen(at.longValue(), now);
            } else if ("drain".equals(marker.get("_id"))) {
                CompletableFuture<Void> waiting = drains.get(marker.getString("token"));
                if (waiting != null) {
                    waiting.complete(null);
                }
            }
        }
    }

    private void probe() {
        long now = System.currentTimeMillis();
        try {
            markers.replaceOne(eq("_id", "probe"), new Document("_id", "probe").append("at", now),
                    new ReplaceOptions().upsert(true));
            status.probeSent(now);
        } catch (RuntimeException e) {
            log.debug("Reverse probe not written: {}", e.toString());
        }
    }

    private void release(String why) {
        drains.values().forEach(drain -> drain.completeExceptionally(new IllegalStateException(why)));
        drains.clear();
    }
}
