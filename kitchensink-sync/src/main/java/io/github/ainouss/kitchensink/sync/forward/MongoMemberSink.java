package io.github.ainouss.kitchensink.sync.forward;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.or;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.COLLECTION;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.ID;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.SYNC;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.SYNC_LSN;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.SYNC_TX_LSN;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.WriteConcern;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.DeleteOneModel;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.ainouss.kitchensink.sync.member.SyncVersion;

/**
 * Applies legacy changes to the {@code members} collection.
 *
 * <p>A write applies only if the stored document is not newer ({@link SyncVersion}), so a replayed or late change is
 * skipped. Changes are applied in the legacy's commit order, because the unique email index must see an address
 * change hands in that order. A row MongoDB refuses goes to {@code migration_quarantine} and the batch goes on. Other
 * errors are thrown so the caller retries the whole batch.
 */
public class MongoMemberSink {

    public static final String QUARANTINE = "migration_quarantine";

    private static final Logger log = LoggerFactory.getLogger(MongoMemberSink.class);
    private static final int DUPLICATE_KEY = 11000;

    private final MongoCollection<Document> members;
    private final MongoCollection<Document> quarantine;

    public MongoMemberSink(MongoDatabase database) {
        this.members = database.getCollection(COLLECTION).withWriteConcern(WriteConcern.MAJORITY);
        this.quarantine = database.getCollection(QUARANTINE).withWriteConcern(WriteConcern.MAJORITY);
    }

    /** What one batch did; the counts add up to the batch size. */
    public record ApplyResult(int upserted, int deleted, int skipped, int quarantined) {

        public static final ApplyResult NONE = new ApplyResult(0, 0, 0, 0);
    }

    private enum Outcome { WRITTEN, STALE, QUARANTINED }

    public ApplyResult apply(List<MemberChange> changes) {
        Outcome[] outcomes = new Outcome[changes.size()];
        List<Integer> writable = new ArrayList<>(changes.size());
        for (int i = 0; i < changes.size(); i++) {
            if (changes.get(i) instanceof MemberChange.Unmappable unmappable) {
                quarantine(unmappable.id(), unmappable.version(), unmappable.reason(), unmappable.row());
                outcomes[i] = Outcome.QUARANTINED;
            } else {
                writable.add(i);
            }
        }
        long deleted = 0;
        int from = 0;
        while (from < writable.size()) {
            List<Integer> rest = writable.subList(from, writable.size());
            List<WriteModel<Document>> models = rest.stream().map(i -> model(changes.get(i))).toList();
            try {
                BulkWriteResult done = members.bulkWrite(models, new BulkWriteOptions().ordered(true));
                deleted += done.getDeletedCount();
                rest.forEach(i -> outcomes[i] = Outcome.WRITTEN);
                from = writable.size();
            } catch (MongoBulkWriteException e) {
                if (e.getWriteConcernError() != null || e.getWriteErrors().isEmpty()) {
                    throw e; // not about one change: the caller retries the whole batch
                }
                BulkWriteError error = e.getWriteErrors().getFirst();
                int failed = error.getIndex();
                deleted += e.getWriteResult().getDeletedCount();
                rest.subList(0, failed).forEach(i -> outcomes[i] = Outcome.WRITTEN);
                int index = rest.get(failed);
                MemberChange change = changes.get(index);
                if (isStale(error)) {
                    outcomes[index] = Outcome.STALE;
                } else {
                    Document row = change instanceof MemberChange.Upsert upsert ? upsert.document() : null;
                    quarantine(change.id(), change.version(), reason(error), row);
                    outcomes[index] = Outcome.QUARANTINED;
                }
                from += failed + 1;
            }
        }
        return summarize(changes, outcomes, deleted);
    }

    private ApplyResult summarize(List<MemberChange> changes, Outcome[] outcomes, long deleted) {
        int upserted = 0;
        int deletes = 0;
        int stale = 0;
        int quarantined = 0;
        Map<Long, Outcome> last = new HashMap<>();
        for (int i = 0; i < changes.size(); i++) {
            MemberChange change = changes.get(i);
            switch (outcomes[i]) {
                case WRITTEN -> {
                    if (change instanceof MemberChange.Delete) {
                        deletes++;
                    } else {
                        upserted++;
                    }
                }
                case STALE -> stale++;
                case QUARANTINED -> quarantined++;
            }
            if (change.id() != null) {
                last.put(change.id(), outcomes[i]);
            }
        }
        // A member whose last change in the batch was written leaves the quarantine.
        resolveQuarantine(last.entrySet().stream().filter(e -> e.getValue() == Outcome.WRITTEN)
                .map(Map.Entry::getKey).toList());
        // A delete that matched nothing is skipped, not an error.
        int skippedDeletes = deletes - (int) deleted;
        return new ApplyResult(upserted, (int) deleted, stale + skippedDeletes, quarantined);
    }

    private static WriteModel<Document> model(MemberChange change) {
        return switch (change) {
            case MemberChange.Upsert upsert ->
                    new ReplaceOneModel<>(guard(upsert.id(), upsert.version()), upsert.document(),
                            new ReplaceOptions().upsert(true));
            case MemberChange.Delete delete -> new DeleteOneModel<>(guard(delete.id(), delete.version()));
            case MemberChange.Unmappable unmappable ->
                    throw new IllegalStateException("an unmappable change is quarantined, never written");
        };
    }

    /** Matches document {@code id} only if it is not newer than {@code version}. */
    static Bson guard(Long id, SyncVersion version) {
        String txLsn = SYNC + "." + SYNC_TX_LSN;
        String lsn = SYNC + "." + SYNC_LSN;
        return and(eq(ID, id), or(
                exists(txLsn, false),
                lt(txLsn, version.txLsn()),
                and(eq(txLsn, version.txLsn()), lte(lsn, version.lsn()))));
    }

    /** A duplicate key on {@code _id} means the stored document is newer. */
    private static boolean isStale(BulkWriteError error) {
        if (error.getCode() != DUPLICATE_KEY) {
            return false;
        }
        BsonDocument details = error.getDetails();
        if (details != null && details.containsKey("keyPattern")) {
            BsonDocument keyPattern = details.getDocument("keyPattern");
            return keyPattern.size() == 1 && keyPattern.containsKey(ID);
        }
        return error.getMessage() != null && error.getMessage().contains("index: _id_ ");
    }

    private static String reason(BulkWriteError error) {
        String category = ErrorCategory.fromErrorCode(error.getCode()).name();
        return "MongoDB refused the write (" + category + ", code " + error.getCode() + "): " + error.getMessage();
    }

    private void quarantine(Long id, SyncVersion version, String reason, Document row) {
        Object key = id != null ? id : "unknown@" + version;
        Document entry = new Document(ID, key)
                .append("reason", reason)
                .append("row", row)
                .append(SYNC_TX_LSN, version.txLsn())
                .append(SYNC_LSN, version.lsn())
                .append("at", Date.from(Instant.now()));
        quarantine.replaceOne(eq(ID, key), entry, new ReplaceOptions().upsert(true));
        log.warn("Member {} quarantined at {}: {}", key, version, reason);
    }

    /** Removes from quarantine the members whose latest change has now been written. */
    private void resolveQuarantine(List<Long> written) {
        if (written.isEmpty() || quarantine.estimatedDocumentCount() == 0) {
            return;
        }
        long resolved = quarantine.deleteMany(in(ID, written)).getDeletedCount();
        if (resolved > 0) {
            log.info("{} member(s) left the quarantine: a later change applied", resolved);
        }
    }
}
