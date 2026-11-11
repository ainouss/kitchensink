package io.github.ainouss.kitchensink.sync.reverse;

import static com.mongodb.client.model.Filters.eq;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import io.github.ainouss.kitchensink.sync.SyncProperties;
import io.github.ainouss.kitchensink.sync.member.MemberDocumentFields;

/**
 * Moves the id sequences at cutover and rollback, so ids from the two sides never collide. The cutover puts
 * MongoDB's counter above every id the legacy may still hand out (including its cached block); the rollback puts
 * {@code member_seq} above every id on either side. Both only move a sequence up and are safe to repeat.
 */
public class Sequences {

    static final String COUNTERS = "sequences";
    static final String MEMBERS = "members";
    static final long MARGIN = 100;

    private final SyncProperties.Legacy legacy;
    private final MongoCollection<Document> counters;
    private final MongoCollection<Document> members;

    public Sequences(SyncProperties.Legacy legacy, MongoDatabase database) {
        this.legacy = legacy;
        this.counters = database.getCollection(COUNTERS).withWriteConcern(WriteConcern.MAJORITY);
        this.members = database.getCollection(MemberDocumentFields.COLLECTION);
    }

    /** @return MongoDB's counter after the move; the next new id is above it */
    public long seedTarget() throws SQLException {
        long legacyTop;
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT GREATEST(COALESCE((SELECT max(id) FROM member), 0), (SELECT last_value FROM member_seq))")) {
            rs.next();
            legacyTop = rs.getLong(1);
        }
        long target = Math.max(legacyTop, mongoMaxId()) + MARGIN;
        counters.updateOne(eq("_id", MEMBERS), Updates.max("value", target), new UpdateOptions().upsert(true));
        return counters.find(eq("_id", MEMBERS)).first().get("value", Number.class).longValue();
    }

    /** @return {@code member_seq}'s value after the move; the legacy's next ids are above it */
    public long moveLegacySequence() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT setval('member_seq', GREATEST("
                     + "COALESCE((SELECT max(id) FROM member), 0), " + mongoMaxId() + ", "
                     + "(SELECT last_value FROM member_seq)) + " + MARGIN + ")")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long mongoMaxId() {
        Document top = members.find().sort(Sorts.descending(MemberDocumentFields.ID)).limit(1).first();
        return top == null ? 0 : top.get(MemberDocumentFields.ID, Number.class).longValue();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(legacy.jdbcUrl(), legacy.user(), legacy.password());
    }
}
