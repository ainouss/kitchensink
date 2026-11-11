package io.github.ainouss.kitchensink.member.adapter.out.mongo;

import static io.github.ainouss.kitchensink.member.adapter.out.mongo.MemberDocuments.BY_NAME;
import static io.github.ainouss.kitchensink.member.adapter.out.mongo.MemberDocuments.COLLECTION;
import static io.github.ainouss.kitchensink.member.adapter.out.mongo.MemberDocuments.EMAIL;
import static io.github.ainouss.kitchensink.member.adapter.out.mongo.MemberDocuments.ID;
import static io.github.ainouss.kitchensink.member.adapter.out.mongo.MemberDocuments.JSON_SCHEMA;
import static io.github.ainouss.kitchensink.member.adapter.out.mongo.MemberDocuments.NAME;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import io.github.ainouss.kitchensink.member.domain.Member;

/**
 * Sets up the {@code members} collection at start-up: schema validator, unique email index (R4, R12), name index
 * (R8), id counter, and the legacy's member 0 when the database is empty. Safe to run again.
 */
@Component
class MembersCollection implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MembersCollection.class);
    static final Member LEGACY_SEED = new Member(0, "John Smith", "john.smith@mailinator.com", "2125551212");

    private final MongoDatabase database;
    private final MongoMemberIds ids;
    private final boolean seed;

    MembersCollection(MongoTemplate mongo, MongoMemberIds ids, @Value("${kitchensink.seed:false}") boolean seed) {
        this.database = mongo.getDb();
        this.ids = ids;
        this.seed = seed;
    }

    @Override
    public void afterPropertiesSet() {
        ensure();
    }

    void ensure() {
        ValidationOptions validation = new ValidationOptions().validator(JSON_SCHEMA)
                .validationLevel(ValidationLevel.MODERATE).validationAction(ValidationAction.ERROR);
        boolean exists = database.listCollectionNames().into(new java.util.ArrayList<>()).contains(COLLECTION);
        if (exists) {
            database.runCommand(new Document("collMod", COLLECTION).append("validator", JSON_SCHEMA)
                    .append("validationLevel", "moderate").append("validationAction", "error"));
        } else {
            database.createCollection(COLLECTION, new CreateCollectionOptions().validationOptions(validation));
        }
        MongoCollection<Document> members = database.getCollection(COLLECTION);
        members.createIndex(Indexes.ascending(EMAIL), new IndexOptions().name("email_unique").unique(true));
        members.createIndex(Indexes.ascending(NAME, ID), new IndexOptions().name("name_id").collation(BY_NAME));

        Document highest = members.find().sort(Sorts.descending(ID)).limit(1).first();
        if (highest != null) {
            ids.atLeast(highest.get(ID, Number.class).longValue());
        } else if (seed) {
            members.insertOne(MemberDocuments.toDocument(LEGACY_SEED));
            log.info("Empty store: the legacy's seed member 0 inserted");
        }
        log.info("Collection {} ready: validator, indexes email_unique and name_id", COLLECTION);
    }
}
