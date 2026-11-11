package io.github.ainouss.kitchensink.sync.member;

/**
 * Field names of the {@code members} document. They must match the application's names; a test checks this.
 *
 * <pre>{ _id: Long, name, email, phoneNumber, schemaVersion: 1, quality?: [String], sync?: {...} }</pre>
 */
public final class MemberDocumentFields {

    public static final String COLLECTION = "members";
    public static final String ID = "_id";
    public static final String NAME = "name";
    public static final String EMAIL = "email";
    public static final String PHONE_NUMBER = "phoneNumber";
    public static final String SCHEMA_VERSION = "schemaVersion";
    public static final String QUALITY = "quality";
    public static final String SYNC = "sync";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Where a document came from, and its source log position (see {@link SyncVersion}). */
    public static final String SYNC_ORIGIN = "origin";
    public static final String SYNC_TX_LSN = "txLsn";
    public static final String SYNC_LSN = "lsn";
    public static final String SYNC_AT = "at";
    public static final String ORIGIN_LEGACY = "LEGACY";

    private MemberDocumentFields() {
    }
}
