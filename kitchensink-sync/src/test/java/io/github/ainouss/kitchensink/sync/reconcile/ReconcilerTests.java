package io.github.ainouss.kitchensink.sync.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import jakarta.validation.Validation;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ainouss.kitchensink.sync.SyncProperties;
import io.github.ainouss.kitchensink.sync.SyncTestStores;
import io.github.ainouss.kitchensink.sync.forward.MongoMemberSink;
import io.github.ainouss.kitchensink.sync.member.MemberRowMapper;
import io.github.ainouss.kitchensink.sync.reconcile.ReconciliationReport.Cause;
import io.github.ainouss.kitchensink.sync.reconcile.ReconciliationReport.Difference;

/** Checks the reconciliation on real stores: seeded data in PostgreSQL, its copy in MongoDB. */
class ReconcilerTests {

    private MongoDatabase database;
    private MongoCollection<Document> members;
    private Reconciler reconciler;

    @BeforeEach
    void aSyncedCopy() throws Exception {
        SyncTestStores.resetLegacy();
        SyncTestStores.seedLegacy(3_000);
        database = SyncTestStores.newMongoDatabase();
        members = database.getCollection("members");
        SyncTestStores.copyLegacyTo(database);
        SyncProperties properties = new SyncProperties(SyncTestStores.legacyProperties(), null,
                SyncTestStores.reconcileProperties(), SyncTestStores.reverseProperties());
        reconciler = new Reconciler(properties, SyncTestStores.mongoTemplate(database),
                new MemberRowMapper(Validation.buildDefaultValidatorFactory().getValidator()), Clock.systemUTC());
    }

    @Test
    void a_synced_copy_is_green_and_identical_and_the_report_is_kept() {
        ReconciliationReport report = reconciler.reconcile();

        assertThat(report.green()).isTrue();
        assertThat(report.identical()).isTrue();
        assertThat(report.legacyCount()).isEqualTo(3_001);
        assertThat(report.mongoCount()).isEqualTo(3_001);
        assertThat(report.legacyDigest()).hasSize(64).isEqualTo(report.mongoDigest());
        assertThat(report.counts()).containsEntry(Cause.QUARANTINED, 0L);
        assertThat(report.inFlight()).isZero();
        assertThat(report.samples()).isEmpty();
        assertThat(report.legacyReadLsn()).matches("[0-9A-F]+/[0-9A-F]+");

        assertThat(reconciler.latest().id()).isEqualTo(report.id()).isNotNull();
        assertThat(reconciler.latest().green()).isTrue();
    }

    @Test
    void a_tampered_field_a_deleted_document_and_an_extra_one_are_red_each_with_its_cause() {
        members.updateOne(Filters.eq("_id", 5L), Updates.set("email", "tampered@mailinator.com"));
        members.deleteOne(Filters.eq("_id", 6L));
        members.insertOne(new Document("_id", 999_999L).append("name", "Nobody").append("email", "nobody@mailinator.com")
                .append("phoneNumber", "2125550000").append("schemaVersion", 1));
        // Member 7 is also missing, but quarantined: counted apart, not as a difference.
        members.deleteOne(Filters.eq("_id", 7L));
        database.getCollection(MongoMemberSink.QUARANTINE)
                .insertOne(new Document("_id", 7L).append("reason", "test"));

        ReconciliationReport report = reconciler.reconcile();

        assertThat(report.green()).isFalse();
        assertThat(report.identical()).isFalse();
        assertThat(report.count(Cause.DIFFERENT)).isEqualTo(1);
        assertThat(report.count(Cause.MISSING_IN_MONGODB)).isEqualTo(1);
        assertThat(report.count(Cause.EXTRA_IN_MONGODB)).isEqualTo(1);
        assertThat(report.count(Cause.QUARANTINED)).isEqualTo(1);
        assertThat(report.samples()).extracting(Difference::cause, Difference::id).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(Cause.DIFFERENT, 5L),
                org.assertj.core.groups.Tuple.tuple(Cause.MISSING_IN_MONGODB, 6L),
                org.assertj.core.groups.Tuple.tuple(Cause.EXTRA_IN_MONGODB, 999_999L));
        Difference tampered = report.samples().stream().filter(d -> d.cause() == Cause.DIFFERENT).findFirst()
                .orElseThrow();
        assertThat(tampered.fields()).containsExactly(new CanonicalRecord.FieldDifference("email",
                "member5@mailinator.com", "tampered@mailinator.com"));
    }

    @Test
    void a_difference_the_stream_settles_before_the_second_look_is_in_flight_not_red() {
        Document eight = members.find(Filters.eq("_id", 8L)).first();
        members.deleteOne(Filters.eq("_id", 8L));
        reconciler.beforeSecondLook(() -> members.insertOne(eight)); // the stream catches up meanwhile

        ReconciliationReport report = reconciler.reconcile();

        assertThat(report.green()).isTrue();
        assertThat(report.inFlight()).isEqualTo(1);
        assertThat(report.count(Cause.MISSING_IN_MONGODB)).isZero();
    }

    @Test
    void a_legacy_change_committed_after_the_consistent_read_and_already_applied_is_in_flight_not_red() {
        // MongoDB has a change the first legacy read missed. The second look sees it in PostgreSQL too.
        members.updateOne(Filters.eq("_id", 9L), Updates.set("name", "Changed Meanwhile"));
        reconciler.beforeSecondLook(() -> {
            try {
                SyncTestStores.legacySql("UPDATE member SET name = 'Changed Meanwhile' WHERE id = 9");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        ReconciliationReport report = reconciler.reconcile();

        assertThat(report.green()).isTrue();
        assertThat(report.inFlight()).isEqualTo(1);
    }

    @Test
    void the_canonical_form_is_unambiguous_and_unicode_normalized() {
        var a = new io.github.ainouss.kitchensink.sync.member.MemberRow(1L, "a|b", "c", "d");
        var b = new io.github.ainouss.kitchensink.sync.member.MemberRow(1L, "a", "b|c", "d");
        assertThat(CanonicalRecord.form(a)).isNotEqualTo(CanonicalRecord.form(b));

        var composed = new io.github.ainouss.kitchensink.sync.member.MemberRow(2L, "Zoë", "z@b.c", "2125550000");
        var decomposed = new io.github.ainouss.kitchensink.sync.member.MemberRow(2L, "Zoë", "z@b.c", "2125550000");
        assertThat(CanonicalRecord.sha256(composed)).isEqualTo(CanonicalRecord.sha256(decomposed));
        assertThat(CanonicalRecord.differences(composed, decomposed)).isEmpty();
        assertThat(CanonicalRecord.form(new io.github.ainouss.kitchensink.sync.member.MemberRow(3L, null, "x", "y")))
                .isEqualTo("3|\\0|x|y");
    }

    @Test
    void only_one_run_at_a_time() throws Exception {
        var second = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        reconciler.beforeSecondLook(() -> {
            try {
                reconciler.reconcile();
            } catch (Throwable t) {
                second.set(t);
            }
        });
        members.deleteOne(Filters.eq("_id", 10L)); // a difference, so the second look happens

        reconciler.reconcile();

        assertThat(second.get()).isInstanceOf(Reconciler.AlreadyRunningException.class);
    }
}
