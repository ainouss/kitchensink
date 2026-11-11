package io.github.ainouss.kitchensink.sync;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import org.bson.Document;
import io.github.ainouss.kitchensink.local.LegacyDatabase;
import io.github.ainouss.kitchensink.local.LocalMongo;
import io.github.ainouss.kitchensink.member.adapter.out.mongo.MemberDocuments;

/**
 * Starts a real PostgreSQL (legacy schema) and a MongoDB replica set once per test run, from downloaded binaries.
 * Each test gets its own fresh MongoDB database.
 */
public final class SyncTestStores {

    public static final String SYNC_USER = LegacyDatabase.SYNC_USER;
    public static final String SYNC_PASSWORD = LegacyDatabase.SYNC_PASSWORD;
    public static final String PUBLICATION = LegacyDatabase.PUBLICATION;

    static final LegacyDatabase LEGACY;
    static final LocalMongo MONGODB;

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final MongoClient MONGO_CLIENT;

    static {
        try {
            LEGACY = LegacyDatabase.start(LocalMongo.freePort(), null, null);
            MONGODB = LocalMongo.start(LocalMongo.freePort(), null);
        } catch (Exception e) {
            throw new IllegalStateException("the test databases did not start", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            MONGODB.close();
            try {
                LEGACY.close();
            } catch (java.io.IOException ignored) {
                // the JVM is exiting
            }
        }));
        MONGO_CLIENT = MongoClients.create(MONGODB.uri(""));
    }

    private SyncTestStores() {
    }

    /** Reconciliation settings for tests: no schedule, short settle time. */
    public static SyncProperties.Reconcile reconcileProperties() {
        return new SyncProperties.Reconcile(false, java.time.Duration.ofMinutes(10), java.time.Duration.ofMillis(300),
                10_000, 20);
    }

    /** Reverse stream settings for tests: not started with the worker, fast probe. */
    public static SyncProperties.Reverse reverseProperties() {
        return new SyncProperties.Reverse(false, java.time.Duration.ofMillis(200));
    }

    public static SyncProperties.Legacy legacyProperties() {
        return new SyncProperties.Legacy(LEGACY.jdbcUrl(), SYNC_USER, SYNC_PASSWORD);
    }

    public static String legacyJdbcUrl() {
        return LEGACY.jdbcUrl();
    }

    public static String mongoUri(String database) {
        return MONGODB.uri(database);
    }

    /** Opens a connection as the legacy database owner. */
    public static Connection legacyOwner() throws SQLException {
        return LEGACY.owner();
    }

    public static void legacySql(String... statements) throws SQLException {
        try (Connection connection = legacyOwner(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** Resets the legacy table to member 0 only and drops slots left by earlier tests. */
    public static void resetLegacy() throws SQLException {
        try (Connection connection = legacyOwner(); Statement statement = connection.createStatement()) {
            List<String> slots = new ArrayList<>();
            try (var rs = statement.executeQuery("SELECT slot_name FROM pg_replication_slots WHERE NOT active")) {
                while (rs.next()) {
                    slots.add(rs.getString(1));
                }
            }
            for (String slot : slots) {
                statement.execute("SELECT pg_drop_replication_slot('" + slot + "')");
            }
            statement.execute("TRUNCATE member");
            statement.execute("INSERT INTO member (id, name, email, phone_number) "
                    + "VALUES (0, 'John Smith', 'john.smith@mailinator.com', '2125551212')");
        }
    }

    /** Adds {@code rows} generated members after member 0. */
    public static void seedLegacy(int rows) throws Exception {
        LEGACY.seed(rows);
    }

    /** Drops a replication slot once its stream has released it. */
    public static void dropSlot(String slot) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            try (Connection connection = legacyOwner(); Statement statement = connection.createStatement();
                 var rs = statement.executeQuery("SELECT active FROM pg_replication_slots WHERE slot_name = '"
                         + slot + "'")) {
                if (!rs.next()) {
                    return;
                }
                if (!rs.getBoolean(1)) {
                    statement.execute("SELECT pg_drop_replication_slot('" + slot + "')");
                    return;
                }
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("slot " + slot + " still active");
    }

    /** Creates a new MongoDB database with the {@code members} collection set up as the application does. */
    public static MongoDatabase newMongoDatabase() {
        MongoDatabase database = MONGO_CLIENT.getDatabase("sync_test_" + DATABASES.incrementAndGet());
        database.createCollection(MemberDocuments.COLLECTION, new CreateCollectionOptions().validationOptions(
                new ValidationOptions().validator(MemberDocuments.JSON_SCHEMA)
                        .validationLevel(ValidationLevel.MODERATE).validationAction(ValidationAction.ERROR)));
        MongoCollection<Document> members = database.getCollection(MemberDocuments.COLLECTION);
        // Same indexes as the application: unique email, and sort by name.
        members.createIndex(Indexes.ascending(MemberDocuments.EMAIL), new IndexOptions().name("email_unique")
                .unique(true));
        members.createIndex(Indexes.ascending(MemberDocuments.NAME, MemberDocuments.ID),
                new IndexOptions().name("name_id").collation(MemberDocuments.BY_NAME));
        return database;
    }

    /** A MongoTemplate on a test database. The reconciliation saves its reports with it. */
    public static org.springframework.data.mongodb.core.MongoTemplate mongoTemplate(MongoDatabase database) {
        return new org.springframework.data.mongodb.core.MongoTemplate(MONGO_CLIENT, database.getName());
    }

    /** Copies the legacy table into {@code database}, as the initial snapshot would. */
    public static void copyLegacyTo(MongoDatabase database) throws Exception {
        var mapper = new io.github.ainouss.kitchensink.sync.member.MemberRowMapper(
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        var sink = new io.github.ainouss.kitchensink.sync.forward.MongoMemberSink(database);
        var version = io.github.ainouss.kitchensink.sync.member.SyncVersion.INITIAL_SNAPSHOT;
        List<io.github.ainouss.kitchensink.sync.forward.MemberChange> batch = new ArrayList<>();
        try (Connection connection = legacyOwner(); Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT id, name, email, phone_number FROM member ORDER BY id")) {
            while (rs.next()) {
                var row = new io.github.ainouss.kitchensink.sync.member.MemberRow(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getString(4));
                batch.add(new io.github.ainouss.kitchensink.sync.forward.MemberChange.Upsert(row.id(), version,
                        mapper.toDocument(row, version, java.time.Instant.now()), true));
                if (batch.size() == 1_000) {
                    sink.apply(batch);
                    batch.clear();
                }
            }
        }
        sink.apply(batch);
    }

    /** A new database without the collection, as if the application never ran. */
    public static MongoDatabase emptyMongoDatabase() {
        return MONGO_CLIENT.getDatabase("sync_test_empty_" + DATABASES.incrementAndGet());
    }
}
