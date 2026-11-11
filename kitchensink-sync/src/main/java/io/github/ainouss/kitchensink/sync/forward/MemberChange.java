package io.github.ainouss.kitchensink.sync.forward;

import org.bson.Document;

import io.github.ainouss.kitchensink.sync.member.SyncVersion;

/** One change to the legacy {@code member} table, ready for MongoDB. */
public sealed interface MemberChange {

    SyncVersion version();

    /** The member's id, or null if the row has none. */
    Long id();

    /** Insert, update or snapshot read: replace the document unless it is newer. */
    record Upsert(Long id, SyncVersion version, Document document, boolean snapshot) implements MemberChange {
    }

    /** The row was deleted: delete the document unless it is newer. */
    record Delete(Long id, SyncVersion version) implements MemberChange {
    }

    /** A row that cannot be stored: it goes to quarantine with the reason, and the stream goes on. */
    record Unmappable(Long id, SyncVersion version, String reason, Document row) implements MemberChange {
    }
}
