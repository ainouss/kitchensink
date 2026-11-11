package io.github.ainouss.kitchensink.sync.forward;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import io.github.ainouss.kitchensink.sync.SyncProperties;

/**
 * Queries the legacy database about the forward stream: the log position and the replication slot. Also writes two
 * markers into the signal table. The probe carries a timestamp, to measure the lag. The drain marker carries a
 * token; changes arrive in commit order, so once it arrives, everything committed before it has been applied.
 */
public class LegacyWal {

    public static final String SIGNAL_TABLE = "debezium_signal";
    public static final String PROBE = "kitchensink-probe";
    public static final String DRAIN = "kitchensink-drain";

    private final SyncProperties.Legacy legacy;

    public LegacyWal(SyncProperties.Legacy legacy) {
        this.legacy = legacy;
    }

    /** The replication slot's state and the current log position. */
    public record SlotState(String slot, boolean exists, boolean active, String currentLsn, String confirmedFlushLsn,
                            long retainedBytes) {
    }

    public SlotState slot(String slot) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement select = connection.prepareStatement("""
                     SELECT pg_current_wal_lsn()::text,
                            s.active,
                            s.confirmed_flush_lsn::text,
                            pg_wal_lsn_diff(pg_current_wal_lsn(), s.restart_lsn)::bigint
                     FROM (SELECT 1) AS one
                     LEFT JOIN pg_replication_slots s ON s.slot_name = ?""")) {
            select.setString(1, slot);
            try (ResultSet rs = select.executeQuery()) {
                rs.next();
                boolean exists = rs.getString(3) != null || rs.getObject(2) != null;
                return new SlotState(slot, exists, rs.getBoolean(2), rs.getString(1), rs.getString(3),
                        exists ? rs.getLong(4) : 0);
            }
        }
    }

    /** Writes the probe with the time it was written. */
    public void probe(long sentAtMillis) throws SQLException {
        mark(PROBE, "{\"message\":\"kitchensink probe\",\"at\":" + sentAtMillis + "}");
    }

    /** Writes the drain marker with its token. */
    public void drainMarker(String token) throws SQLException {
        mark(DRAIN, "{\"message\":\"kitchensink drain\",\"token\":\"" + token + "\"}");
    }

    private void mark(String id, String data) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement upsert = connection.prepareStatement("INSERT INTO " + SIGNAL_TABLE
                     + " (id, type, data) VALUES (?, 'log', ?) ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data")) {
            upsert.setString(1, id);
            upsert.setString(2, data);
            upsert.executeUpdate();
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(legacy.jdbcUrl(), legacy.user(), legacy.password());
    }

    /** Formats a log position the way PostgreSQL does ({@code 16/B374D848}). */
    public static String lsn(long value) {
        return (Long.toHexString(value >>> 32) + "/" + Long.toHexString(value & 0xFFFFFFFFL)).toUpperCase();
    }
}
