package io.github.ainouss.kitchensink.member.adapter.out.mongo;

import static com.mongodb.client.model.Filters.eq;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import io.github.ainouss.kitchensink.member.application.MemberIds;

/**
 * New member ids from a counter document, {@code sequences/members}, increased atomically.
 * The counter only goes up, so a new id never reuses one a migrated member already has.
 */
@Component
public class MongoMemberIds implements MemberIds {

    static final String COLLECTION = "sequences";
    static final String MEMBERS = "members";
    static final String VALUE = "value";

    private final MongoCollection<Document> sequences;

    MongoMemberIds(MongoTemplate mongo) {
        this.sequences = mongo.getCollection(COLLECTION);
    }

    @Override
    public long next() {
        Document counter = sequences.findOneAndUpdate(eq("_id", MEMBERS), Updates.inc(VALUE, 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return counter.get(VALUE, Number.class).longValue();
    }

    /** Raises the counter to {@code value} if it is lower. Never lowers it. */
    public void atLeast(long value) {
        sequences.updateOne(eq("_id", MEMBERS), Updates.max(VALUE, value),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
    }
}
