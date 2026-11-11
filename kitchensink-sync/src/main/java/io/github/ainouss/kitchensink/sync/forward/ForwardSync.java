package io.github.ainouss.kitchensink.sync.forward;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.mongodb.MongoException;
import com.mongodb.client.MongoDatabase;
import io.debezium.embedded.Connect;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.debezium.engine.format.ChangeEventFormat;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import io.github.ainouss.kitchensink.sync.SyncDirection;
import io.github.ainouss.kitchensink.sync.SyncProperties;
import io.github.ainouss.kitchensink.sync.member.MemberDocumentFields;
import io.github.ainouss.kitchensink.sync.member.MemberRowMapper;
import io.github.ainouss.kitchensink.sync.member.SyncVersion;

/**
 * The forward stream: Debezium reads PostgreSQL (a snapshot first, then the change stream) and each batch is applied
 * to MongoDB before it is acknowledged. Offsets are stored in MongoDB, so a restart resumes; a replayed batch is
 * harmless. If MongoDB fails, the batch is retried and PostgreSQL keeps its log until then. The application must
 * create the {@code members} collection first. See docs/migration.md.
 */
public class ForwardSync implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ForwardSync.class);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final SyncProperties properties;
    private final MongoDatabase database;
    private final ChangeEvents events;
    private final MongoMemberSink sink;
    private final ForwardSyncStatus status;
    private final LegacyWal wal;
    private final SyncDirection direction;
    private final Map<String, CompletableFuture<Void>> drains = new ConcurrentHashMap<>();

    private volatile DebeziumEngine<RecordChangeEvent<SourceRecord>> engine;
    private volatile ExecutorService executor;
    private volatile ScheduledExecutorService prober;
    private volatile String offsetsToken;
    private volatile boolean running;

    public ForwardSync(SyncProperties properties, MongoDatabase database, MemberRowMapper mapper,
                       ForwardSyncStatus status) {
        this.properties = properties;
        this.database = database;
        this.events = new ChangeEvents(mapper);
        this.sink = new MongoMemberSink(database);
        this.status = status;
        this.wal = new LegacyWal(properties.legacy());
        this.direction = new SyncDirection(database, properties);
    }

    /** Starts with the worker if the forward direction is recorded, or if enabled before any direction is. */
    @Override
    public boolean isAutoStartup() {
        return direction.startsAtStartup(SyncDirection.Direction.FORWARD);
    }

    /** Starts the stream. On failure it cleans up, marks the status FAILED and rethrows, so a later start can retry. */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        String token = null;
        DebeziumEngine<RecordChangeEvent<SourceRecord>> built = null;
        ExecutorService runner = null;
        ScheduledExecutorService probes = null;
        try {
            requireMembersCollection();
            status.starting();
            token = UUID.randomUUID().toString();
            offsetsToken = token;
            MongoOffsetBackingStore.register(token, database);
            built = buildEngine();
            runner = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform().name("forward-sync").unstarted(task));
            probes = Executors.newSingleThreadScheduledExecutor(task -> Thread.ofVirtual().name("forward-probe")
                    .unstarted(task));
            engine = built;
            executor = runner;
            prober = probes;
            running = true;
            runner.execute(built);
            long every = properties.forward().probeInterval().toMillis();
            probes.scheduleWithFixedDelay(this::probe, every, every, TimeUnit.MILLISECONDS);
            direction.record(SyncDirection.Direction.FORWARD);
        } catch (RuntimeException | ServiceConfigurationError | LinkageError e) {
            running = false;
            if (probes != null) {
                probes.shutdownNow();
            }
            if (runner != null) {
                runner.shutdownNow();
            }
            closeQuietly(built);
            if (token != null) {
                MongoOffsetBackingStore.unregister(token);
            }
            status.failed("The forward stream did not start: " + e);
            log.error("The forward stream did not start", e);
            throw e;
        }
        log.info("Forward stream starting: slot {}, publication {}, {}", properties.forward().slot(),
                properties.forward().publication(), properties.legacy().jdbcUrl());
    }

    /** Builds the Debezium engine. Tests override it. */
    DebeziumEngine<RecordChangeEvent<SourceRecord>> buildEngine() {
        return DebeziumEngine.create(ChangeEventFormat.of(Connect.class))
                .using(debeziumProperties())
                .notifying(this::handleBatch)
                .using((success, message, error) -> {
                    if (success || !running) {
                        log.info("Forward stream ended: {}", message);
                        status.stopped();
                    } else {
                        log.error("Forward stream failed: {}", message, error);
                        status.failed(message + (error == null ? "" : ": " + error));
                        failed();
                    }
                })
                .build();
    }

    private static void closeQuietly(DebeziumEngine<?> built) {
        if (built == null) {
            return;
        }
        try {
            built.close();
        } catch (IOException | RuntimeException e) {
            log.debug("An engine that never ran did not close cleanly: {}", e.toString());
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            prober.shutdownNow();
            try {
                engine.close();
            } catch (IllegalStateException alreadyDown) {
                log.debug("The engine had already shut down: {}", alreadyDown.getMessage());
            }
            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Forward stream still stopping after 30 s");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            MongoOffsetBackingStore.unregister(offsetsToken);
            drains.values().forEach(drain -> drain.completeExceptionally(new IllegalStateException("stopped")));
            drains.clear();
            status.stopped();
            log.info("Forward stream stopped; the slot keeps the legacy's changes until it starts again");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** The engine stopped on an error: release the rest so a later start can retry. */
    private synchronized void failed() {
        if (!running) {
            return;
        }
        running = false;
        prober.shutdownNow();
        executor.shutdown();
        MongoOffsetBackingStore.unregister(offsetsToken);
        drains.values().forEach(drain -> drain.completeExceptionally(new IllegalStateException("failed")));
        drains.clear();
    }

    /**
     * Waits until every earlier PostgreSQL commit has been applied to MongoDB, by writing a marker and waiting for it
     * to come through the stream.
     *
     * @return how long it took
     * @throws TimeoutException when the marker has not come through within {@code within}
     */
    public Duration drain(Duration within) throws TimeoutException, SQLException, InterruptedException {
        if (!running) {
            throw new IllegalStateException("The forward stream is not running: nothing to drain");
        }
        long start = System.nanoTime();
        String token = UUID.randomUUID().toString();
        CompletableFuture<Void> applied = new CompletableFuture<>();
        drains.put(token, applied);
        try {
            wal.drainMarker(token);
            applied.get(within.toMillis(), TimeUnit.MILLISECONDS);
            return Duration.ofNanos(System.nanoTime() - start);
        } catch (ExecutionException e) {
            throw new IllegalStateException("The drain did not complete: " + e.getCause().getMessage(), e);
        } finally {
            drains.remove(token);
        }
    }

    /** Reads the replication slot's current state. */
    public LegacyWal.SlotState slot() throws SQLException {
        LegacyWal.SlotState slot = wal.slot(properties.forward().slot());
        status.slot(slot);
        return slot;
    }

    private void probe() {
        long now = System.currentTimeMillis();
        try {
            wal.probe(now);
            status.probeSent(now);
        } catch (SQLException | RuntimeException e) {
            log.debug("Probe not written: {}", e.toString());
        }
    }

    void handleBatch(List<RecordChangeEvent<SourceRecord>> records,
                     DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer)
            throws InterruptedException {
        List<MemberChange> changes = new ArrayList<>(records.size());
        List<ChangeEvents.Marker> markers = new ArrayList<>();
        long snapshotRows = 0;
        SyncVersion last = null;
        Instant lastCommit = null;
        for (RecordChangeEvent<SourceRecord> event : records) {
            SourceRecord record = event.record();
            Optional<MemberChange> change = events.toChange(record);
            if (change.isPresent()) {
                MemberChange c = change.get();
                changes.add(c);
                if (c instanceof MemberChange.Upsert upsert && upsert.snapshot()) {
                    snapshotRows++;
                }
                last = c.version();
                lastCommit = committedAt(record);
            } else {
                events.toMarker(record).ifPresent(markers::add);
            }
        }
        MongoMemberSink.ApplyResult result = changes.isEmpty() ? MongoMemberSink.ApplyResult.NONE : applied(changes);
        for (RecordChangeEvent<SourceRecord> event : records) {
            committer.markProcessed(event);
        }
        committer.markBatchFinished();
        status.batch(result, snapshotRows, last, lastCommit);
        // Everything before the markers is now applied, so they can be reported.
        long now = System.currentTimeMillis();
        for (ChangeEvents.Marker marker : markers) {
            switch (marker) {
                case ChangeEvents.Marker.Probe probe -> status.probeSeen(probe.sentAtMillis(), now);
                case ChangeEvents.Marker.Drain drain -> {
                    CompletableFuture<Void> waiting = drains.get(drain.token());
                    if (waiting != null) {
                        waiting.complete(null);
                    }
                }
            }
        }
        if (result.quarantined() > 0 || result.skipped() > 0) {
            log.info("Batch of {}: {} upserted, {} deleted, {} skipped as stale, {} quarantined", changes.size(),
                    result.upserted(), result.deleted(), result.skipped(), result.quarantined());
        }
    }

    /** Applies the batch, retrying while MongoDB is failing. */
    private MongoMemberSink.ApplyResult applied(List<MemberChange> changes) throws InterruptedException {
        Duration backoff = Duration.ofMillis(500);
        while (true) {
            try {
                return sink.apply(changes);
            } catch (MongoException e) {
                if (!running) {
                    throw new InterruptedException("stopping while MongoDB fails: the batch is not acknowledged and "
                            + "will be replayed from the stored offset");
                }
                log.warn("A batch of {} changes was not applied, retried in {} ms: {}", changes.size(),
                        backoff.toMillis(), e.toString());
                Thread.sleep(backoff.toMillis());
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
        }
    }

    private static Instant committedAt(SourceRecord record) {
        Struct source = ((Struct) record.value()).getStruct("source");
        return Instant.ofEpochMilli(source.getInt64("ts_ms"));
    }

    private void requireMembersCollection() {
        Document info = database.listCollections()
                .filter(new Document("name", MemberDocumentFields.COLLECTION)).first();
        Document options = info == null ? null : info.get("options", Document.class);
        if (options == null || !options.containsKey("validator")) {
            throw new IllegalStateException("The '" + MemberDocumentFields.COLLECTION + "' collection, with its "
                    + "validator, does not exist in database '" + database.getName() + "': start the application "
                    + "once first (KITCHENSINK_SEED=false); it creates the collection, its validator and its indexes");
        }
    }

    Properties debeziumProperties() {
        SyncProperties.Legacy legacy = properties.legacy();
        SyncProperties.Forward forward = properties.forward();
        Properties p = new Properties();
        p.setProperty("name", "kitchensink-forward");
        p.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        p.setProperty("offset.storage", MongoOffsetBackingStore.class.getName());
        p.setProperty(MongoOffsetBackingStore.DATABASE_REF, offsetsToken);
        p.setProperty(MongoOffsetBackingStore.KEY, "forward:" + forward.slot());
        p.setProperty("offset.flush.interval.ms", "1000");
        p.setProperty("database.hostname", legacy.host());
        p.setProperty("database.port", Integer.toString(legacy.port()));
        p.setProperty("database.user", legacy.user());
        p.setProperty("database.password", legacy.password());
        p.setProperty("database.dbname", legacy.database());
        p.setProperty("topic.prefix", "kitchensink");
        p.setProperty("plugin.name", "pgoutput");
        p.setProperty("slot.name", forward.slot());
        p.setProperty("publication.name", forward.publication());
        // The table owner creates the publication, so the worker's account does not need to own the table.
        p.setProperty("publication.autocreate.mode", "disabled");
        // Capture the signal table too: on-demand snapshots, and the worker's probe and drain markers.
        p.setProperty("table.include.list", "public.member,public." + LegacyWal.SIGNAL_TABLE);
        p.setProperty("signal.enabled.channels", "source");
        p.setProperty("signal.data.collection", "public." + LegacyWal.SIGNAL_TABLE);
        p.setProperty("snapshot.mode", "initial");
        p.setProperty("tombstones.on.delete", "false");
        p.setProperty("skipped.operations", "t");
        p.setProperty("max.batch.size", Integer.toString(forward.batchSize()));
        p.setProperty("max.queue.size", Integer.toString(forward.batchSize() * 4));
        p.setProperty("heartbeat.interval.ms", Long.toString(forward.heartbeat().toMillis()));
        p.setProperty("slot.drop.on.stop", "false");
        return p;
    }
}
