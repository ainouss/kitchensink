package io.github.ainouss.kitchensink.sync;

import static com.mongodb.client.model.Filters.eq;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;

/**
 * Which direction the worker runs: forward or reverse, one at a time. The last stream started is recorded in
 * MongoDB ({@code sync_state}), and a restarted worker starts that one again.
 *
 * <p>The environment ({@code SYNC_FORWARD_ENABLED}, {@code SYNC_REVERSE_ENABLED}) only decides until a stream has
 * started once. Delete the record to give the choice back to the environment.
 */
public final class SyncDirection {

    public enum Direction { FORWARD, REVERSE }

    /** The recorded direction and when it was written. */
    public record Recorded(Direction direction, Instant at) {
    }

    public static final String COLLECTION = "sync_state";
    static final String ID = "direction";

    private final MongoCollection<Document> state;
    private final SyncProperties properties;

    public SyncDirection(MongoDatabase database, SyncProperties properties) {
        this.state = database.getCollection(COLLECTION).withWriteConcern(WriteConcern.MAJORITY);
        this.properties = properties;
    }

    /** The direction of the last stream started, if any. */
    public Optional<Recorded> recorded() {
        Document document = state.find(eq("_id", ID)).first();
        if (document == null) {
            return Optional.empty();
        }
        return Optional.of(new Recorded(Direction.valueOf(document.getString("direction")),
                document.getDate("at").toInstant()));
    }

    /** Records that {@code direction} now runs, so a restarted worker starts it again. */
    public void record(Direction direction) {
        state.replaceOne(eq("_id", ID), new Document("_id", ID).append("direction", direction.name())
                .append("at", new Date()), new ReplaceOptions().upsert(true));
    }

    /** Whether this stream starts with the worker: the recorded direction wins, else the environment decides. */
    public boolean startsAtStartup(Direction direction) {
        return recorded().map(recorded -> recorded.direction() == direction).orElseGet(() -> switch (direction) {
            case FORWARD -> properties.forward() != null && properties.forward().enabled();
            case REVERSE -> properties.reverse() != null && properties.reverse().enabled();
        });
    }
}
