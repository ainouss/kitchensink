package io.github.ainouss.kitchensink.sync.forward;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;

import io.github.ainouss.kitchensink.sync.member.MemberRow;
import io.github.ainouss.kitchensink.sync.member.MemberRowMapper;
import io.github.ainouss.kitchensink.sync.member.SyncVersion;
import io.github.ainouss.kitchensink.sync.member.UnmappableRowException;

/**
 * Turns Debezium change events into {@link MemberChange}s. Events for other tables (heartbeats, signals) are
 * ignored here.
 */
final class ChangeEvents {

    private static final Pattern SEQUENCE = Pattern.compile("\\[\\s*(null|\"(\\d+)\")\\s*,\\s*(null|\"(\\d+)\")\\s*]");

    private final MemberRowMapper mapper;

    ChangeEvents(MemberRowMapper mapper) {
        this.mapper = mapper;
    }

    Optional<MemberChange> toChange(SourceRecord record) {
        if (!(record.value() instanceof Struct envelope) || envelope.schema().field("op") == null) {
            return Optional.empty();
        }
        Struct source = envelope.getStruct("source");
        if (source == null || !MemberRow.TABLE.equals(source.getString("table"))
                || !"public".equals(source.getString("schema"))) {
            return Optional.empty();
        }
        String op = envelope.getString("op");
        SyncVersion version = version(source);
        Instant at = Instant.ofEpochMilli(source.getInt64("ts_ms"));
        return switch (op) {
            case "r", "c", "u" -> Optional.of(upsert(envelope.getStruct("after"), version, at, "r".equals(op)
                    && isInitialSnapshot(source)));
            case "d" -> Optional.of(delete(envelope.getStruct("before"), version));
            default -> Optional.empty(); // truncate and message events are not used
        };
    }

    /** The worker's own probe and drain markers, as they come through the stream (see {@link LegacyWal}). */
    sealed interface Marker {
        record Probe(long sentAtMillis) implements Marker {
        }

        record Drain(String token) implements Marker {
        }
    }

    private static final Pattern PROBE_AT = Pattern.compile("\"at\":(\\d+)");
    private static final Pattern DRAIN_TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

    Optional<Marker> toMarker(SourceRecord record) {
        if (!(record.value() instanceof Struct envelope) || envelope.schema().field("op") == null) {
            return Optional.empty();
        }
        Struct source = envelope.getStruct("source");
        String op = envelope.getString("op");
        if (source == null || !LegacyWal.SIGNAL_TABLE.equals(source.getString("table"))
                || !("c".equals(op) || "u".equals(op))) {
            return Optional.empty();
        }
        Struct after = envelope.getStruct("after");
        String id = after.getString("id");
        String data = after.getString("data");
        if (data == null) {
            return Optional.empty();
        }
        if (LegacyWal.PROBE.equals(id)) {
            Matcher at = PROBE_AT.matcher(data);
            return at.find() ? Optional.of(new Marker.Probe(Long.parseLong(at.group(1)))) : Optional.empty();
        }
        if (LegacyWal.DRAIN.equals(id)) {
            Matcher token = DRAIN_TOKEN.matcher(data);
            return token.find() ? Optional.of(new Marker.Drain(token.group(1))) : Optional.empty();
        }
        return Optional.empty();
    }

    private MemberChange upsert(Struct after, SyncVersion version, Instant at, boolean snapshot) {
        MemberRow row;
        try {
            row = row(after);
        } catch (RuntimeException e) {
            return new MemberChange.Unmappable(idOf(after), version, "unreadable row: " + e.getMessage(),
                    after == null ? null : MemberRowMapper.rawRow(new MemberRow(idOf(after), null, null, null)));
        }
        try {
            return new MemberChange.Upsert(row.id(), version, mapper.toDocument(row, version, at), snapshot);
        } catch (UnmappableRowException e) {
            return new MemberChange.Unmappable(row.id(), version, e.getMessage(), MemberRowMapper.rawRow(row));
        }
    }

    private static MemberChange delete(Struct before, SyncVersion version) {
        Long id = idOf(before);
        if (id == null) {
            return new MemberChange.Unmappable(null, version, "a delete without the row's id (replica identity?)",
                    null);
        }
        return new MemberChange.Delete(id, version);
    }

    private static MemberRow row(Struct after) {
        if (after == null) {
            throw new IllegalArgumentException("no row values in the event");
        }
        return new MemberRow(after.getInt64(MemberRow.ID), after.getString(MemberRow.NAME),
                after.getString(MemberRow.EMAIL), after.getString(MemberRow.PHONE_NUMBER));
    }

    private static Long idOf(Struct row) {
        if (row == null) {
            return null;
        }
        Field id = row.schema().field(MemberRow.ID);
        return id == null ? null : (Long) row.get(id);
    }

    /** The event's source log position (see {@link SyncVersion}); snapshot rows come before every change. */
    static SyncVersion version(Struct source) {
        if (isInitialSnapshot(source)) {
            return SyncVersion.INITIAL_SNAPSHOT;
        }
        Long lsn = source.getInt64("lsn");
        long change = lsn == null ? 0 : lsn;
        Field sequenceField = source.schema().field("sequence");
        String sequence = sequenceField == null ? null : source.getString("sequence");
        long commit = 0;
        if (sequence != null) {
            Matcher matcher = SEQUENCE.matcher(sequence);
            if (matcher.matches() && matcher.group(2) != null) {
                commit = Long.parseLong(matcher.group(2));
            }
        }
        return new SyncVersion(commit, change);
    }

    /** Whether the row comes from the initial snapshot (not an incremental one). */
    static boolean isInitialSnapshot(Struct source) {
        String snapshot = source.getString("snapshot");
        return snapshot != null && !"false".equals(snapshot) && !"incremental".equals(snapshot);
    }
}
