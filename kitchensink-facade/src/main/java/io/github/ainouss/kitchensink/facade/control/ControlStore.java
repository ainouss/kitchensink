package io.github.ainouss.kitchensink.facade.control;

import static com.mongodb.client.model.Filters.eq;

import java.time.Clock;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the control document in memory and reloads it every second, so every facade instance follows a change
 * quickly and no request waits on MongoDB. If MongoDB is unreachable, the last known control stays in force.
 */
@Component
public class ControlStore {

    public static final String COLLECTION = "migration_control";
    public static final String ID = "members";

    private static final Logger log = LoggerFactory.getLogger(ControlStore.class);

    private final MongoCollection<Document> controls;
    private final Clock clock;
    private final AtomicReference<Control> current = new AtomicReference<>(Control.INITIAL);

    public ControlStore(MongoTemplate mongo) {
        this.controls = mongo.getDb().getCollection(COLLECTION);
        this.clock = Clock.systemUTC();
    }

    public Control current() {
        return current.get();
    }

    @Scheduled(fixedDelayString = "${kitchensink.facade.control-refresh:1s}")
    public void refresh() {
        try {
            Document document = controls.find(eq("_id", ID)).first();
            Control read = document == null ? Control.INITIAL : fromDocument(document);
            Control previous = current.getAndSet(read);
            if (!read.equals(previous)) {
                log.info("Routing now reads={} writes={} (set by {} at {})", read.reads(), read.writes(),
                        read.updatedBy(), read.updatedAt());
            }
        } catch (RuntimeException e) {
            log.warn("Control document not readable, routing stays reads={} writes={}: {}", current().reads(),
                    current().writes(), e.toString());
        }
    }

    /** Saves the control and applies it here at once; other instances pick it up on their next refresh. */
    public Control set(Control.Reads reads, Control.Writes writes, String by) {
        Control control = new Control(reads, writes, clock.instant(), by);
        controls.replaceOne(eq("_id", ID), new Document("_id", ID)
                        .append("reads", reads.name())
                        .append("writes", writes.name())
                        .append("updatedAt", Date.from(control.updatedAt()))
                        .append("updatedBy", by),
                new ReplaceOptions().upsert(true));
        current.set(control);
        log.info("Routing set to reads={} writes={} by {}", reads, writes, by);
        return control;
    }

    private static Control fromDocument(Document document) {
        Date at = document.getDate("updatedAt");
        return new Control(Control.Reads.valueOf(document.getString("reads")),
                Control.Writes.valueOf(document.getString("writes")),
                at == null ? java.time.Instant.EPOCH : at.toInstant(), document.getString("updatedBy"));
    }
}
