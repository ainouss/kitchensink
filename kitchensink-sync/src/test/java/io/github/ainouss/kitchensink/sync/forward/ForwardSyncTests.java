package io.github.ainouss.kitchensink.sync.forward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.validation.Validation;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import org.apache.kafka.connect.source.SourceRecord;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ainouss.kitchensink.sync.SyncDirection;
import io.github.ainouss.kitchensink.sync.SyncProperties;
import io.github.ainouss.kitchensink.sync.SyncTestStores;
import io.github.ainouss.kitchensink.sync.member.MemberRowMapper;

/**
 * Tests the forward stream end to end: PostgreSQL, Debezium Embedded, then MongoDB.
 * Each test uses its own replication slot and MongoDB database.
 */
class ForwardSyncTests {

    private static final Duration WAIT = Duration.ofSeconds(90);
    private static final AtomicInteger SLOTS = new AtomicInteger();

    private final MemberRowMapper mapper =
            new MemberRowMapper(Validation.buildDefaultValidatorFactory().getValidator());
    private final List<ForwardSync> started = new ArrayList<>();
    private final Map<ForwardSync, ForwardSyncStatus> statuses = new HashMap<>();
    private String slot;
    private MongoDatabase database;
    private MongoCollection<Document> members;

    @BeforeEach
    void freshStores() throws Exception {
        SyncTestStores.resetLegacy();
        slot = "ks10_test_" + SLOTS.incrementAndGet();
        database = SyncTestStores.newMongoDatabase();
        members = database.getCollection("members");
    }

    @AfterEach
    void stopAndDropTheSlot() throws Exception {
        started.forEach(ForwardSync::stop);
        SyncTestStores.dropSlot(slot);
    }

    @Test
    void the_snapshot_copies_every_row_unchanged_and_flags_the_ones_todays_rules_refuse() throws Exception {
        SyncTestStores.seedLegacy(3_000);
        ForwardSyncStatus status = start();

        await().atMost(WAIT).until(() -> status.snapshotRows() == 3_001);
        assertThat(members.countDocuments()).isEqualTo(3_001);
        assertThat(status.quarantined()).isZero();

        // Every row, every value, unchanged.
        List<List<Object>> rows = new ArrayList<>();
        try (Connection connection = SyncTestStores.legacyOwner(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT id, name, email, phone_number FROM member ORDER BY id")) {
            while (rs.next()) {
                rows.add(List.of(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        }
        List<List<Object>> documents = new ArrayList<>();
        members.find().sort(Sorts.ascending("_id")).forEach(d -> documents.add(
                List.of(d.getLong("_id"), d.getString("name"), d.getString("email"), d.getString("phoneNumber"))));
        assertThat(documents).isEqualTo(rows);

        // The seeded bad rows are flagged, never corrected.
        assertThat(quality(997)).containsExactly("NAME_HAS_DIGIT");
        assertThat(quality(1009)).containsExactly("NAME_LENGTH");
        assertThat(quality(1013)).containsExactly("PHONE_FORMAT");
        assertThat(quality(1019)).containsExactly("PHONE_FORMAT");
        assertThat(quality(1021)).containsExactly("EMAIL_SYNTAX");
        assertThat(quality(1031)).as("an upper-case email is valid (R4)").isEmpty();
        assertThat(quality(1)).isEmpty();
        assertThat(members.countDocuments(Filters.exists("quality"))).isEqualTo(
                count("SELECT count(*) FROM member WHERE id > 0 AND (id % 997 = 0 OR id % 1009 = 0 "
                        + "OR id % 1013 = 0 OR id % 1019 = 0 OR id % 1021 = 0)"));
        Document sync = member(1).get("sync", Document.class);
        assertThat(sync.getString("origin")).isEqualTo("LEGACY");
        assertThat(sync.getLong("txLsn")).as("snapshot rows sit below every streamed change").isZero();
    }

    @Test
    void inserts_updates_and_deletes_are_streamed_in_the_legacys_commit_order() throws Exception {
        ForwardSyncStatus status = start();
        await().atMost(WAIT).until(() -> status.snapshotRows() == 1);

        SyncTestStores.legacySql(
                "INSERT INTO member VALUES (10, 'a@mailinator.com', 'Ada Lovelace', '2125550010')",
                "INSERT INTO member VALUES (11, 'b@mailinator.com', 'Grace Hopper', '2125550011')");
        // First transaction: 11 takes a@ from 10. Second: rename 10, delete 11, insert 12 with b@.
        try (Connection connection = SyncTestStores.legacyOwner(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("UPDATE member SET email = 'c@mailinator.com' WHERE id = 10");
            statement.execute("UPDATE member SET email = 'a@mailinator.com' WHERE id = 11");
            connection.commit();
            statement.execute("UPDATE member SET name = 'Ada King' WHERE id = 10");
            statement.execute("DELETE FROM member WHERE id = 11");
            statement.execute("INSERT INTO member VALUES (12, 'b@mailinator.com', 'Hedy Lamarr', '2125550012')");
            connection.commit();
        }

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(member(12)).isNotNull();
            assertThat(member(11)).isNull();
        });
        assertThat(member(10).getString("name")).isEqualTo("Ada King");
        assertThat(member(10).getString("email")).isEqualTo("c@mailinator.com");
        assertThat(member(12).getString("email")).isEqualTo("b@mailinator.com");
        assertThat(members.countDocuments()).isEqualTo(3);
        assertThat(member(10).get("sync", Document.class).getLong("txLsn")).isPositive();
        // Counters update after the batch: wait for them.
        await().atMost(WAIT).untilAsserted(() -> assertThat(status.deleted()).isEqualTo(1));
        assertThat(status.quarantined()).isZero();
        // Member 10 changed in three transactions and none was skipped: versions follow commit order.
        assertThat(status.skipped()).isZero();
    }

    @Test
    void a_restart_resumes_from_the_stored_offset_without_a_new_snapshot_and_loses_nothing() throws Exception {
        SyncTestStores.legacySql("INSERT INTO member SELECT i, 'm' || i || '@mailinator.com', 'Member Ab', "
                + "'2125550000' FROM generate_series(1, 100) AS i");
        ForwardSync first = forwardSync(new ForwardSyncStatus());
        ForwardSyncStatus firstStatus = status(first);
        first.start();
        await().atMost(WAIT).until(() -> firstStatus.snapshotRows() == 101);
        Thread.sleep(1_500); // wait for one offset flush (every 1 s)
        first.stop();

        // Changes made while the worker is down.
        SyncTestStores.legacySql(
                "INSERT INTO member SELECT i, 'm' || i || '@mailinator.com', 'Member Cd', '2125550000' "
                        + "FROM generate_series(101, 150) AS i",
                "UPDATE member SET name = 'Renamed While Down' WHERE id = 5",
                "DELETE FROM member WHERE id = 6");

        ForwardSyncStatus secondStatus = new ForwardSyncStatus();
        ForwardSync second = forwardSync(secondStatus);
        second.start();
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(members.countDocuments()).isEqualTo(150);
            assertThat(member(5).getString("name")).isEqualTo("Renamed While Down");
            assertThat(member(6)).isNull();
        });
        assertThat(secondStatus.snapshotRows()).as("resumed from the offset, no second snapshot").isZero();
        // Counters update after the batch: wait for them.
        await().atMost(WAIT).untilAsserted(() -> assertThat(secondStatus.upserted()).isGreaterThanOrEqualTo(51));
        // The stream position is stored in MongoDB, next to the data.
        Document offsets = database.getCollection(MongoOffsetBackingStore.COLLECTION)
                .find(Filters.eq("_id", "forward:" + slot)).first();
        assertThat(offsets).isNotNull();
        assertThat(offsets.getList("entries", Document.class)).singleElement()
                .satisfies(entry -> assertThat(entry.getString("valueText")).contains("\"lsn\""));
    }

    @Test
    void stopped_then_started_again_it_resumes_without_a_new_snapshot() throws Exception {
        ForwardSyncStatus status = new ForwardSyncStatus();
        ForwardSync sync = forwardSync(status);
        sync.start();
        await().atMost(WAIT).until(() -> status.snapshotRows() == 1);

        sync.stop();
        assertThat(status.state()).isEqualTo(ForwardSyncStatus.State.STOPPED);
        assertThat(status.lagSeconds(System.currentTimeMillis())).as("no lag while stopped").isNull();
        SyncTestStores.legacySql("INSERT INTO member SELECT i, 'stop' || i || '@mailinator.com', 'Member Ef', "
                + "'2125550000' FROM generate_series(1, 30) AS i");
        Thread.sleep(500);
        assertThat(members.countDocuments()).isEqualTo(1);

        sync.start();
        await().atMost(WAIT).untilAsserted(() -> assertThat(members.countDocuments()).isEqualTo(31));
        assertThat(status.snapshotRows()).isEqualTo(1);
    }

    @Test
    void after_a_burst_the_lag_goes_back_to_zero() throws Exception {
        ForwardSyncStatus status = start();
        await().atMost(WAIT).until(() -> status.snapshotRows() == 1);
        await().atMost(WAIT).until(() -> status.lagSeconds(System.currentTimeMillis()) != null);

        SyncTestStores.legacySql("DO $$ BEGIN FOR b IN 0..199 LOOP INSERT INTO member SELECT i, 'b' || i || "
                + "'@mailinator.com', 'Burst Member', '2125550000' FROM generate_series(1000 + b * 100, 1000 + b * 100 "
                + "+ 99) AS i; COMMIT; END LOOP; END $$");

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(members.countDocuments()).isEqualTo(20_001);
            assertThat(status.lagSeconds(System.currentTimeMillis())).isLessThan(1.0);
        });
    }

    @Test
    void a_drain_returns_once_everything_committed_before_it_is_applied() throws Exception {
        ForwardSyncStatus status = new ForwardSyncStatus();
        ForwardSync sync = forwardSync(status);
        sync.start();
        await().atMost(WAIT).until(() -> status.snapshotRows() == 1);

        SyncTestStores.legacySql("INSERT INTO member SELECT i, 'd' || i || '@mailinator.com', 'Drain Member', "
                + "'2125550000' FROM generate_series(1, 5000) AS i");
        Duration took = sync.drain(Duration.ofSeconds(60));

        assertThat(members.countDocuments()).as("no wait after the drain").isEqualTo(5_001);
        assertThat(took).isLessThan(Duration.ofSeconds(60));
    }

    @Test
    void a_row_the_store_refuses_is_quarantined_and_the_stream_goes_on() throws Exception {
        ForwardSyncStatus status = start();
        await().atMost(WAIT).until(() -> status.snapshotRows() == 1);
        members.insertOne(new Document("_id", 900_000L).append("name", "Intruder")
                .append("email", "taken@mailinator.com").append("phoneNumber", "2125550000").append("schemaVersion", 1));

        SyncTestStores.legacySql(
                "INSERT INTO member VALUES (20, 'taken@mailinator.com', 'Ada Lovelace', '2125550020')",
                "INSERT INTO member VALUES (21, 'free@mailinator.com', 'Grace Hopper', '2125550021')");

        await().atMost(WAIT).untilAsserted(() -> assertThat(member(21)).isNotNull());
        MongoCollection<Document> quarantine = database.getCollection(MongoMemberSink.QUARANTINE);
        assertThat(quarantine.find(Filters.eq("_id", 20L)).first().getString("reason")).contains("email_unique");
        // Counters update after the batch: wait for them.
        await().atMost(WAIT).untilAsserted(() -> assertThat(status.quarantined()).isEqualTo(1));
        assertThat(status.state()).isEqualTo(ForwardSyncStatus.State.RUNNING);

        members.deleteOne(Filters.eq("_id", 900_000L));
        SyncTestStores.legacySql("UPDATE member SET name = 'Ada King' WHERE id = 20");
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(member(20)).isNotNull();
            assertThat(quarantine.countDocuments()).isZero();
        });
    }

    @Test
    void a_stream_that_fails_is_reported_failed_with_its_reason() {
        // A missing publication: Debezium cannot stream and gives the reason.
        SyncProperties broken = new SyncProperties(properties().legacy(), new SyncProperties.Forward(true, slot,
                "no_such_publication", 500, Duration.ofSeconds(1), Duration.ofMillis(200)), properties().reconcile(),
                properties().reverse());
        ForwardSyncStatus status = new ForwardSyncStatus();
        ForwardSync sync = new ForwardSync(broken, database, mapper, status);
        started.add(sync);
        sync.start();

        await().atMost(WAIT).until(() -> status.state() == ForwardSyncStatus.State.FAILED);
        assertThat(status.failure()).isNotBlank();
        assertThat(io.github.ainouss.kitchensink.sync.status.ForwardSyncHealthProbe.health(status)).isEqualTo("DOWN");
        assertThat(io.github.ainouss.kitchensink.sync.status.ForwardSyncHealthProbe.health(new ForwardSyncStatus()))
                .as("a stream that is merely stopped is healthy").isEqualTo("UP");
    }

    @Test
    void a_started_forward_stream_records_its_direction_so_a_restarted_worker_starts_it_again() throws Exception {
        SyncTestStores.seedLegacy(1);
        ForwardSyncStatus status = start();
        await().atMost(WAIT).until(() -> status.snapshotRows() == 2);

        assertThat(new SyncDirection(database, properties()).recorded()).map(SyncDirection.Recorded::direction)
                .contains(SyncDirection.Direction.FORWARD);
        SyncProperties disabled = new SyncProperties(properties().legacy(), new SyncProperties.Forward(false, slot,
                SyncTestStores.PUBLICATION, 500, Duration.ofSeconds(1), Duration.ofMillis(200)),
                properties().reconcile(), properties().reverse());
        assertThat(new ForwardSync(disabled, database, mapper, new ForwardSyncStatus()).isAutoStartup())
                .as("the record wins over the environment").isTrue();
    }

    @Test
    void the_stream_refuses_to_start_before_the_application_created_the_collection() {
        database = SyncTestStores.emptyMongoDatabase();
        ForwardSyncStatus status = new ForwardSyncStatus();
        ForwardSync sync = new ForwardSync(properties(), database, mapper, status);

        assertThatThrownBy(sync::start).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("start the application once first");
        assertThat(status.state()).isEqualTo(ForwardSyncStatus.State.FAILED);
        assertThat(status.failure()).contains("start the application once first");
    }

    @Test
    void a_start_that_throws_is_reported_failed_releases_what_it_took_and_can_be_tried_again() throws Exception {
        // Simulates a deleted jar: the engine cannot be built.
        SyncTestStores.seedLegacy(10);
        ForwardSyncStatus status = new ForwardSyncStatus();
        AtomicBoolean jarGone = new AtomicBoolean(true);
        ForwardSync sync = new ForwardSync(properties(), database, mapper, status) {
            @Override
            DebeziumEngine<RecordChangeEvent<SourceRecord>> buildEngine() {
                if (jarGone.get()) {
                    throw new ServiceConfigurationError(
                            "io.debezium.engine.DebeziumEngine$BuilderFactory: Error accessing configuration file");
                }
                return super.buildEngine();
            }
        };
        started.add(sync);
        int registrations = MongoOffsetBackingStore.registrations();

        assertThatThrownBy(sync::start).isInstanceOf(ServiceConfigurationError.class);
        assertThat(status.state()).isEqualTo(ForwardSyncStatus.State.FAILED);
        assertThat(status.failure()).contains("did not start").contains("Error accessing configuration file");
        assertThat(io.github.ainouss.kitchensink.sync.status.ForwardSyncHealthProbe.health(status)).isEqualTo("DOWN");
        assertThat(sync.isRunning()).isFalse();
        assertThat(MongoOffsetBackingStore.registrations()).as("its offsets token released").isEqualTo(registrations);

        jarGone.set(false);
        sync.start();
        await().atMost(WAIT).until(() -> status.snapshotRows() == 11);
        assertThat(status.state()).isEqualTo(ForwardSyncStatus.State.RUNNING);
        assertThat(status.failure()).isNull();
    }

    private ForwardSyncStatus start() {
        ForwardSyncStatus status = new ForwardSyncStatus();
        forwardSync(status).start();
        return status;
    }

    private ForwardSync forwardSync(ForwardSyncStatus status) {
        ForwardSync sync = new ForwardSync(properties(), database, mapper, status);
        started.add(sync);
        statuses.put(sync, status);
        return sync;
    }

    private ForwardSyncStatus status(ForwardSync sync) {
        return statuses.get(sync);
    }

    private SyncProperties properties() {
        return new SyncProperties(
                new SyncProperties.Legacy(SyncTestStores.legacyJdbcUrl(), SyncTestStores.SYNC_USER,
                        SyncTestStores.SYNC_PASSWORD),
                new SyncProperties.Forward(true, slot, SyncTestStores.PUBLICATION, 500, Duration.ofSeconds(1),
                        Duration.ofMillis(200)),
                SyncTestStores.reconcileProperties(), SyncTestStores.reverseProperties());
    }

    private Document member(long id) {
        return members.find(Filters.eq("_id", id)).first();
    }

    private List<String> quality(long id) {
        return MemberRowMapper.quality(member(id));
    }

    private static long count(String sql) throws Exception {
        try (Connection connection = SyncTestStores.legacyOwner(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
