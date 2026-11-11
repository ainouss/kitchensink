package io.github.ainouss.kitchensink.sync.reverse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Validation;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ainouss.kitchensink.sync.SyncDirection;
import io.github.ainouss.kitchensink.sync.SyncProperties;
import io.github.ainouss.kitchensink.sync.SyncTestStores;
import io.github.ainouss.kitchensink.sync.forward.ForwardSync;
import io.github.ainouss.kitchensink.sync.forward.ForwardSyncStatus;
import io.github.ainouss.kitchensink.sync.member.MemberRowMapper;

/**
 * Tests the reverse stream on real stores: what the new application writes in MongoDB reaches PostgreSQL.
 * Also covers restart, loop guard, quarantine, drain and sequences.
 */
class ReverseSyncTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private final MemberRowMapper mapper =
            new MemberRowMapper(Validation.buildDefaultValidatorFactory().getValidator());
    private final List<ReverseSync> started = new ArrayList<>();
    private MongoDatabase database;
    private MongoCollection<Document> members;

    @BeforeEach
    void freshStores() throws Exception {
        SyncTestStores.resetLegacy();
        database = SyncTestStores.newMongoDatabase();
        members = database.getCollection("members");
    }

    @AfterEach
    void stop() {
        started.forEach(ReverseSync::stop);
    }

    @Test
    void what_the_new_application_writes_arrives_in_the_legacy_inserted_updated_and_deleted() throws Exception {
        ReverseSyncStatus status = new ReverseSyncStatus();
        reverse(status).start(true);

        members.insertOne(member(5000, "Ada Lovelace", "ada@mailinator.com"));
        await().atMost(WAIT).until(() -> legacyName(5000) != null);
        assertThat(legacyRow(5000)).containsExactly("Ada Lovelace", "ada@mailinator.com", "2125550000");

        members.updateOne(Filters.eq("_id", 5000L), Updates.set("name", "Ada King"));
        await().atMost(WAIT).until(() -> "Ada King".equals(legacyName(5000)));

        members.deleteOne(Filters.eq("_id", 5000L));
        await().atMost(WAIT).until(() -> legacyName(5000) == null);
        // Counters update after the batch: wait for them.
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(status.upserted()).isEqualTo(2);
            assertThat(status.deleted()).isEqualTo(1);
        });
        await().atMost(WAIT).until(() -> status.lagSeconds(System.currentTimeMillis()) != null);
        assertThat(status.lagSeconds(System.currentTimeMillis())).isLessThan(2.0);
    }

    @Test
    void a_restart_resumes_after_its_token_with_nothing_lost_and_nothing_applied_twice() throws Exception {
        ReverseSyncStatus first = new ReverseSyncStatus();
        ReverseSync sync = reverse(first);
        sync.start(true);
        for (long id = 1000; id < 1010; id++) {
            members.insertOne(member(id, "Member Ab", "m" + id + "@mailinator.com"));
        }
        await().atMost(WAIT).until(() -> legacyCount() == 11);
        Thread.sleep(800); // the resume token is saved on the next loop turn
        sync.stop();

        for (long id = 1010; id < 1020; id++) { // written while the worker is down
            members.insertOne(member(id, "Member Cd", "m" + id + "@mailinator.com"));
        }
        ReverseSyncStatus second = new ReverseSyncStatus();
        reverse(second).start(false);

        await().atMost(WAIT).until(() -> legacyCount() == 21);
        assertThat(second.upserted()).as("only what came after the token").isEqualTo(10);
    }

    @Test
    void a_started_reverse_stream_records_its_direction_so_a_restarted_worker_starts_it_and_not_the_forward_one() {
        reverse(new ReverseSyncStatus()).start(true);

        assertThat(new SyncDirection(database, properties()).recorded()).map(SyncDirection.Recorded::direction)
                .contains(SyncDirection.Direction.REVERSE);
        assertThat(new ReverseSync(properties(), database, mapper, new ReverseSyncStatus(), () -> false)
                .isAutoStartup()).as("disabled in this environment, recorded").isTrue();
        SyncProperties forwardEnabled = new SyncProperties(SyncTestStores.legacyProperties(),
                new SyncProperties.Forward(true, "unused", SyncTestStores.PUBLICATION, 500, Duration.ofSeconds(1),
                        Duration.ofMillis(200)), SyncTestStores.reconcileProperties(), SyncTestStores.reverseProperties());
        assertThat(new ForwardSync(forwardEnabled, database, mapper, new ForwardSyncStatus()).isAutoStartup())
                .as("enabled in this environment, not the recorded direction").isFalse();
    }

    @Test
    void it_refuses_to_start_while_the_forward_stream_runs() {
        ReverseSync sync = new ReverseSync(properties(), database, mapper, new ReverseSyncStatus(), () -> true);

        assertThatThrownBy(() -> sync.start(true)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one direction at a time");
        assertThat(sync.isRunning()).isFalse();
    }

    @Test
    void a_document_that_came_from_the_legacy_is_never_sent_back() throws Exception {
        ReverseSyncStatus status = new ReverseSyncStatus();
        ReverseSync sync = reverse(status);
        sync.start(true);

        members.insertOne(member(6000, "From Legacy", "legacy@mailinator.com")
                .append("sync", new Document("origin", "LEGACY").append("txLsn", 1L).append("lsn", 1L)));
        members.insertOne(member(6001, "From New", "new@mailinator.com"));
        sync.drain(WAIT);

        assertThat(legacyName(6000)).isNull();
        assertThat(legacyName(6001)).isEqualTo("From New");
        await().atMost(WAIT).untilAsserted(() -> assertThat(status.echoesSkipped()).isEqualTo(1));
    }

    @Test
    void a_change_the_legacy_refuses_is_quarantined_and_the_stream_goes_on() throws Exception {
        ReverseSyncStatus status = new ReverseSyncStatus();
        ReverseSync sync = reverse(status);
        sync.start(true);

        // Member 0 in PostgreSQL already has this email, so the copy is refused.
        members.insertOne(member(7000, "Clash", "john.smith@mailinator.com"));
        members.insertOne(member(7001, "Fine", "fine@mailinator.com"));
        sync.drain(WAIT);

        assertThat(legacyName(7001)).isEqualTo("Fine");
        assertThat(legacyName(7000)).isNull();
        Document quarantined = database.getCollection(LegacyWriter.QUARANTINE).find(Filters.eq("_id", 7000L)).first();
        assertThat(quarantined.getString("reason")).contains("23505");
        await().atMost(WAIT).untilAsserted(() -> assertThat(status.quarantined()).isEqualTo(1));
    }

    @Test
    void a_drain_returns_once_every_earlier_write_is_in_the_legacy() throws Exception {
        ReverseSync sync = reverse(new ReverseSyncStatus());
        sync.start(true);
        List<Document> many = new ArrayList<>();
        for (long id = 2000; id < 2500; id++) {
            many.add(member(id, "Drained Member", "d" + id + "@mailinator.com"));
        }
        members.insertMany(many);

        sync.drain(WAIT);

        assertThat(legacyCount()).as("no wait after the drain").isEqualTo(501);
    }

    @Test
    void the_sequences_move_above_whatever_the_other_side_allocated() throws Exception {
        Sequences sequences = new Sequences(SyncTestStores.legacyProperties(), database);
        // Legacy: highest id 1000, sequence at 1100 (Hibernate reserves ids in blocks).
        SyncTestStores.legacySql("INSERT INTO member VALUES (1000, 'x@mailinator.com', 'Member X', '2125550000')",
                "SELECT setval('member_seq', 1100)");

        long target = sequences.seedTarget();
        assertThat(target).as("above the legacy's block, not only its highest id").isGreaterThan(1100);
        assertThat(database.getCollection("sequences").find(Filters.eq("_id", "members")).first()
                .get("value", Number.class).longValue()).isEqualTo(target);

        // The new application uses ids above it. On rollback, the legacy sequence moves above those.
        members.insertOne(member(target + 1, "New Member", "n@mailinator.com"));
        long legacy = sequences.moveLegacySequence();
        assertThat(legacy).isGreaterThan(target + 1);
        assertThat(sequences.seedTarget()).as("idempotent, only ever up").isGreaterThanOrEqualTo(target);
    }

    private ReverseSync reverse(ReverseSyncStatus status) {
        ReverseSync sync = new ReverseSync(properties(), database, mapper, status, () -> false);
        started.add(sync);
        return sync;
    }

    private SyncProperties properties() {
        return new SyncProperties(SyncTestStores.legacyProperties(), null, SyncTestStores.reconcileProperties(),
                SyncTestStores.reverseProperties());
    }

    /** A member as the new application writes it: no {@code sync} field. */
    private static Document member(long id, String name, String email) {
        return new Document("_id", id).append("name", name).append("email", email)
                .append("phoneNumber", "2125550000").append("schemaVersion", 1);
    }

    private static String legacyName(long id) throws Exception {
        List<String> row = legacyRow(id);
        return row == null ? null : row.getFirst();
    }

    private static List<String> legacyRow(long id) throws Exception {
        try (Connection connection = SyncTestStores.legacyOwner(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT name, email, phone_number FROM member WHERE id = " + id)) {
            return rs.next() ? List.of(rs.getString(1), rs.getString(2), rs.getString(3)) : null;
        }
    }

    private static long legacyCount() throws Exception {
        try (Connection connection = SyncTestStores.legacyOwner(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM member")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
