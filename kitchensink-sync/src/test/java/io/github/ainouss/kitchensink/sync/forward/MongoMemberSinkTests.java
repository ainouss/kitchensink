package io.github.ainouss.kitchensink.sync.forward;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Validation;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ainouss.kitchensink.member.adapter.out.mongo.MemberDocuments;
import io.github.ainouss.kitchensink.sync.SyncTestStores;
import io.github.ainouss.kitchensink.sync.forward.MongoMemberSink.ApplyResult;
import io.github.ainouss.kitchensink.sync.member.MemberRow;
import io.github.ainouss.kitchensink.sync.member.MemberRowMapper;
import io.github.ainouss.kitchensink.sync.member.SyncVersion;

/** Checks the sink on a real MongoDB with the application's validator and indexes: version guard, order, quarantine. */
class MongoMemberSinkTests {

    private final MemberRowMapper mapper =
            new MemberRowMapper(Validation.buildDefaultValidatorFactory().getValidator());
    private MongoDatabase database;
    private MongoCollection<Document> members;
    private MongoCollection<Document> quarantine;
    private MongoMemberSink sink;

    @BeforeEach
    void freshDatabase() {
        database = SyncTestStores.newMongoDatabase();
        members = database.getCollection(MemberDocuments.COLLECTION);
        quarantine = database.getCollection(MongoMemberSink.QUARANTINE);
        sink = new MongoMemberSink(database);
    }

    @Test
    void the_same_change_applied_twice_changes_nothing() {
        MemberChange change = upsert(10, "Ada Lovelace", "ada@mailinator.com", new SyncVersion(500, 120));

        assertThat(sink.apply(List.of(change))).isEqualTo(new ApplyResult(1, 0, 0, 0));
        Document first = member(10);
        assertThat(sink.apply(List.of(change))).isEqualTo(new ApplyResult(1, 0, 0, 0));

        assertThat(member(10)).isEqualTo(first);
        assertThat(members.countDocuments()).isEqualTo(1);
    }

    @Test
    void an_older_change_after_a_newer_one_is_skipped() {
        sink.apply(List.of(upsert(10, "New Name", "ada@mailinator.com", new SyncVersion(500, 120))));

        ApplyResult older = sink.apply(List.of(
                upsert(10, "Old Name", "ada@mailinator.com", new SyncVersion(500, 110)),   // same transaction, earlier
                upsert(10, "Older Name", "ada@mailinator.com", new SyncVersion(400, 900)), // earlier transaction
                new MemberChange.Delete(10L, new SyncVersion(499, 1))));                   // an older delete

        assertThat(older).isEqualTo(new ApplyResult(0, 0, 3, 0));
        assertThat(member(10).getString("name")).isEqualTo("New Name");

        assertThat(sink.apply(List.of(new MemberChange.Delete(10L, new SyncVersion(501, 1)))))
                .isEqualTo(new ApplyResult(0, 1, 0, 0));
        assertThat(members.countDocuments()).isZero();
    }

    @Test
    void a_snapshot_row_never_overwrites_a_streamed_change_but_any_streamed_change_overwrites_a_snapshot_row() {
        // A streamed change wins over a snapshot row, even when its own LSN (50) is lower than the snapshot's.
        sink.apply(List.of(upsert(10, "Snapshot Row", "ada@mailinator.com", SyncVersion.INITIAL_SNAPSHOT)));
        assertThat(sink.apply(List.of(upsert(10, "Committed Later", "ada@mailinator.com", new SyncVersion(100, 50)))))
                .isEqualTo(new ApplyResult(1, 0, 0, 0));

        assertThat(sink.apply(List.of(upsert(10, "Snapshot Again", "ada@mailinator.com",
                SyncVersion.INITIAL_SNAPSHOT)))).isEqualTo(new ApplyResult(0, 0, 1, 0));
        assertThat(member(10).getString("name")).isEqualTo("Committed Later");
    }

    @Test
    void a_batch_keeps_the_legacys_order_so_an_email_can_change_hands() {
        sink.apply(List.of(upsert(10, "Ada", "x@mailinator.com", new SyncVersion(100, 1))));

        // One legacy transaction: member 10 gives up x@ and member 11 takes it.
        ApplyResult result = sink.apply(List.of(
                upsert(10, "Ada", "y@mailinator.com", new SyncVersion(200, 5)),
                upsert(11, "Grace", "x@mailinator.com", new SyncVersion(200, 6))));

        assertThat(result).isEqualTo(new ApplyResult(2, 0, 0, 0));
        assertThat(member(11).getString("email")).isEqualTo("x@mailinator.com");
    }

    @Test
    void a_change_the_store_refuses_is_quarantined_the_batch_goes_on_and_a_later_change_resolves_it()
            throws Exception {
        // A document written directly to MongoDB already uses this email.
        members.insertOne(new Document("_id", 900_000L).append("name", "Intruder").append("email", "taken@mailinator.com")
                .append("phoneNumber", "2125550000").append("schemaVersion", 1));

        ApplyResult result = sink.apply(List.of(
                upsert(20, "Ada", "taken@mailinator.com", new SyncVersion(300, 1)),
                upsert(21, "Grace", "grace@mailinator.com", new SyncVersion(300, 2)),
                new MemberChange.Unmappable(22L, new SyncVersion(300, 3), "phone_number is null", null)));

        assertThat(result).isEqualTo(new ApplyResult(1, 0, 0, 2));
        assertThat(member(21)).isNotNull();
        Document quarantined = quarantine.find(Filters.eq("_id", 20L)).first();
        assertThat(quarantined.getString("reason")).contains("DUPLICATE_KEY").contains("email_unique");
        assertThat(quarantined.get("row", Document.class).getString("email")).isEqualTo("taken@mailinator.com");
        assertThat(quarantine.find(Filters.eq("_id", 22L)).first().getString("reason"))
                .isEqualTo("phone_number is null");

        // Free the email and change member 20 again: it is written and leaves the quarantine.
        members.deleteOne(Filters.eq("_id", 900_000L));
        assertThat(sink.apply(List.of(upsert(20, "Ada L", "taken@mailinator.com", new SyncVersion(301, 1)))))
                .isEqualTo(new ApplyResult(1, 0, 0, 0));
        assertThat(quarantine.find(Filters.eq("_id", 20L)).first()).isNull();
        assertThat(quarantine.find(Filters.eq("_id", 22L)).first()).as("still broken: stays").isNotNull();
    }

    @Test
    void what_the_sync_writes_reads_back_through_the_applications_own_mapping() throws Exception {
        sink.apply(List.of(upsert(42, "Zoë Østergaard", "zoe@mailinator.com", new SyncVersion(7, 7))));

        var member = MemberDocuments.toMember(member(42));
        assertThat(member.id()).isEqualTo(42L);
        assertThat(member.name()).isEqualTo("Zoë Østergaard");
        assertThat(member.phoneNumber()).isEqualTo("2125551234");
    }

    private MemberChange upsert(long id, String name, String email, SyncVersion version) {
        try {
            return new MemberChange.Upsert(id, version, mapper.toDocument(new MemberRow(id, name, email, "2125551234"),
                    version, Instant.parse("2026-11-09T10:00:00Z")), version.equals(SyncVersion.INITIAL_SNAPSHOT));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Document member(long id) {
        return members.find(Filters.eq("_id", id)).first();
    }
}
