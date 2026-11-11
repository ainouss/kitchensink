package io.github.ainouss.kitchensink.sync.reverse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.ainouss.kitchensink.sync.SyncProperties;
import io.github.ainouss.kitchensink.sync.member.MemberRow;
import io.github.ainouss.kitchensink.sync.member.MemberRowMapper;

/**
 * Writes MongoDB changes into the legacy {@code member} table, in order, one transaction per batch. The writes are
 * idempotent, so a batch replayed after a crash changes nothing.
 *
 * <p>If PostgreSQL refuses the data, the batch is retried one change at a time; a refused change goes to
 * {@code reverse_quarantine} and the rest goes on. Other errors are thrown so the caller retries the batch.
 */
class LegacyWriter {

    static final String QUARANTINE = "reverse_quarantine";
    private static final Logger log = LoggerFactory.getLogger(LegacyWriter.class);

    private final SyncProperties.Legacy legacy;
    private final MongoCollection<Document> quarantine;

    LegacyWriter(SyncProperties.Legacy legacy, MongoDatabase database) {
        this.legacy = legacy;
        this.quarantine = database.getCollection(QUARANTINE).withWriteConcern(WriteConcern.MAJORITY);
    }

    /** One change for the legacy database: the new row, or its deletion. */
    sealed interface Change {
        long id();

        record Upsert(MemberRow row) implements Change {
            public long id() {
                return row.id();
            }
        }

        record Delete(long id) implements Change {
        }
    }

    record Written(int upserted, int deleted, int quarantined) {
    }

    Written apply(List<Change> changes) throws SQLException {
        if (changes.isEmpty()) {
            return new Written(0, 0, 0);
        }
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                Written written = write(connection, changes);
                connection.commit();
                return written;
            } catch (SQLException e) {
                connection.rollback();
                if (!isDataError(e)) {
                    throw e;
                }
            }
            // A change was refused: retry one by one and quarantine the refused ones.
            int upserted = 0;
            int deleted = 0;
            int quarantined = 0;
            for (Change change : changes) {
                try {
                    Written one = write(connection, List.of(change));
                    connection.commit();
                    upserted += one.upserted();
                    deleted += one.deleted();
                } catch (SQLException e) {
                    connection.rollback();
                    if (!isDataError(e)) {
                        throw e;
                    }
                    quarantine(change, e);
                    quarantined++;
                }
            }
            return new Written(upserted, deleted, quarantined);
        }
    }

    private static Written write(Connection connection, List<Change> changes) throws SQLException {
        int upserted = 0;
        int deleted = 0;
        try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO member (id, name, email, phone_number) VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, email = EXCLUDED.email,
                                               phone_number = EXCLUDED.phone_number""");
             PreparedStatement delete = connection.prepareStatement("DELETE FROM member WHERE id = ?")) {
            for (Change change : changes) {
                switch (change) {
                    case Change.Upsert u -> {
                        upsert.setLong(1, u.row().id());
                        upsert.setString(2, u.row().name());
                        upsert.setString(3, u.row().email());
                        upsert.setString(4, u.row().phoneNumber());
                        upsert.executeUpdate();
                        upserted++;
                    }
                    case Change.Delete d -> {
                        delete.setLong(1, d.id());
                        deleted += delete.executeUpdate();
                    }
                }
            }
        }
        return new Written(upserted, deleted, 0);
    }

    /** SQL states 22 and 23 mean a problem with the row (bad data, constraint), not with the database. */
    static boolean isDataError(SQLException e) {
        String state = e.getSQLState();
        return state != null && (state.startsWith("22") || state.startsWith("23"));
    }

    private void quarantine(Change change, SQLException e) {
        Document row = change instanceof Change.Upsert u ? MemberRowMapper.rawRow(u.row()) : null;
        quarantine.replaceOne(Filters.eq("_id", change.id()), new Document("_id", change.id())
                        .append("reason", "PostgreSQL refused the write (SQL state " + e.getSQLState() + "): "
                                + e.getMessage())
                        .append("row", row)
                        .append("at", Date.from(Instant.now())),
                new ReplaceOptions().upsert(true));
        log.warn("Member {} quarantined on its way back to the legacy: SQL state {}", change.id(), e.getSQLState());
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(legacy.jdbcUrl(), legacy.user(), legacy.password());
    }
}
