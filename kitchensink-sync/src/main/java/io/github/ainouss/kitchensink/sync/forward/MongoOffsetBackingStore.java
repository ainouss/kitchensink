package io.github.ainouss.kitchensink.sync.forward;

import static com.mongodb.client.model.Filters.eq;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import org.apache.kafka.connect.runtime.WorkerConfig;
import org.apache.kafka.connect.storage.MemoryOffsetBackingStore;
import org.bson.Document;
import org.bson.types.Binary;

/**
 * Stores Debezium's offsets in MongoDB ({@code sync_offsets}), so a restart resumes where it stopped. Each entry is
 * kept as binary and as readable text. Debezium creates this class itself, so the database is passed in through
 * {@link #register}.
 */
public class MongoOffsetBackingStore extends MemoryOffsetBackingStore {

    public static final String COLLECTION = "sync_offsets";
    public static final String DATABASE_REF = "offset.storage.mongodb.ref";
    public static final String KEY = "offset.storage.mongodb.key";

    private static final Map<String, MongoDatabase> DATABASES = new ConcurrentHashMap<>();

    private MongoCollection<Document> offsets;
    private String key;

    /** Makes {@code database} available to the store created by the engine configured with {@code token}. */
    public static void register(String token, MongoDatabase database) {
        DATABASES.put(token, database);
    }

    public static void unregister(String token) {
        DATABASES.remove(token);
    }

    /** For tests: how many databases are registered. */
    static int registrations() {
        return DATABASES.size();
    }

    @Override
    public void configure(WorkerConfig config) {
        super.configure(config);
        Map<String, String> originals = config.originalsStrings();
        MongoDatabase database = DATABASES.get(originals.get(DATABASE_REF));
        if (database == null) {
            throw new IllegalStateException("No MongoDB database registered under " + DATABASE_REF + "="
                    + originals.get(DATABASE_REF));
        }
        this.key = originals.get(KEY);
        this.offsets = database.getCollection(COLLECTION).withWriteConcern(WriteConcern.MAJORITY);
    }

    @Override
    public synchronized void start() {
        super.start();
        Document stored = offsets.find(eq("_id", key)).first();
        if (stored == null) {
            return;
        }
        Map<ByteBuffer, ByteBuffer> loaded = new HashMap<>();
        for (Document entry : stored.getList("entries", Document.class)) {
            Binary k = entry.get("key", Binary.class);
            Binary v = entry.get("value", Binary.class);
            loaded.put(ByteBuffer.wrap(k.getData()), v == null ? null : ByteBuffer.wrap(v.getData()));
        }
        data.putAll(loaded);
    }

    @Override
    protected void save() {
        List<Document> entries = new ArrayList<>(data.size());
        for (Map.Entry<ByteBuffer, ByteBuffer> entry : data.entrySet()) {
            byte[] k = bytes(entry.getKey());
            byte[] v = entry.getValue() == null ? null : bytes(entry.getValue());
            entries.add(new Document("key", new Binary(k)).append("value", v == null ? null : new Binary(v))
                    .append("keyText", new String(k, StandardCharsets.UTF_8))
                    .append("valueText", v == null ? null : new String(v, StandardCharsets.UTF_8)));
        }
        offsets.replaceOne(eq("_id", key), new Document("_id", key).append("entries", entries)
                .append("savedAt", new Date()), new ReplaceOptions().upsert(true));
    }

    /** Not used by the embedded engine. */
    @Override
    public Set<Map<String, Object>> connectorPartitions(String connectorName) {
        return Set.of();
    }

    private static byte[] bytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }
}
