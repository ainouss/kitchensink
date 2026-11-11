package io.github.ainouss.kitchensink.sync.member;

import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.CURRENT_SCHEMA_VERSION;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.EMAIL;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.ID;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.NAME;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.ORIGIN_LEGACY;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.PHONE_NUMBER;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.QUALITY;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.SCHEMA_VERSION;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.SYNC;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.SYNC_AT;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.SYNC_LSN;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.SYNC_ORIGIN;
import static io.github.ainouss.kitchensink.sync.member.MemberDocumentFields.SYNC_TX_LSN;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import jakarta.validation.Validator;

import org.bson.Document;
import org.springframework.stereotype.Component;

import io.github.ainouss.kitchensink.member.domain.MemberRules;
import io.github.ainouss.kitchensink.member.domain.Quality;

/**
 * Maps a legacy row to a {@code members} document and back. Sync and reconciliation both use it, so data is always
 * copied and compared the same way. Values are copied unchanged; a row that breaks today's rules is flagged in
 * {@code quality}, not fixed. Only a row with a missing required value is refused.
 */
@Component
public class MemberRowMapper {

    private final Validator validator;

    public MemberRowMapper(Validator validator) {
        this.validator = validator;
    }

    /**
     * The business fields only, without {@code sync} or {@code quality}.
     *
     * @throws UnmappableRowException when a required value is missing
     */
    public Document toBusinessDocument(MemberRow row) throws UnmappableRowException {
        if (row.id() == null) {
            throw new UnmappableRowException("id is null");
        }
        return new Document(ID, row.id())
                .append(NAME, required(row.name(), MemberRow.NAME))
                .append(EMAIL, required(row.email(), MemberRow.EMAIL))
                .append(PHONE_NUMBER, required(row.phoneNumber(), MemberRow.PHONE_NUMBER))
                .append(SCHEMA_VERSION, CURRENT_SCHEMA_VERSION);
    }

    /**
     * The full document the worker writes: business fields, quality flags and sync metadata.
     *
     * @param version the source log position of this change, used to ignore older writes
     * @param at      when the legacy database committed it
     */
    public Document toDocument(MemberRow row, SyncVersion version, Instant at) throws UnmappableRowException {
        Document document = toBusinessDocument(row);
        Set<Quality> quality = MemberRules.quality(validator, row.name(), row.email(), row.phoneNumber());
        if (!quality.isEmpty()) {
            document.append(QUALITY, quality.stream().map(Quality::name).sorted().toList());
        }
        return document.append(SYNC, new Document(SYNC_ORIGIN, ORIGIN_LEGACY)
                .append(SYNC_TX_LSN, version.txLsn())
                .append(SYNC_LSN, version.lsn())
                .append(SYNC_AT, Date.from(at)));
    }

    /** Maps a document back to a row, for the reverse sync and the reconciliation. */
    public MemberRow toRow(Document document) {
        Number id = document.get(ID, Number.class);
        return new MemberRow(id == null ? null : id.longValue(), document.getString(NAME), document.getString(EMAIL),
                document.getString(PHONE_NUMBER));
    }

    /** The raw row as stored in quarantine: every column, nulls included. */
    public static Document rawRow(MemberRow row) {
        Document raw = new Document();
        raw.put(MemberRow.ID, row.id());
        raw.put(MemberRow.NAME, row.name());
        raw.put(MemberRow.EMAIL, row.email());
        raw.put(MemberRow.PHONE_NUMBER, row.phoneNumber());
        return raw;
    }

    private static String required(String value, String column) throws UnmappableRowException {
        if (value == null) {
            throw new UnmappableRowException(column + " is null; the members collection requires it");
        }
        return value;
    }

    /** The document's quality flags, or an empty list. */
    public static List<String> quality(Document document) {
        List<String> quality = document.getList(QUALITY, String.class);
        return quality == null ? List.of() : quality;
    }
}
