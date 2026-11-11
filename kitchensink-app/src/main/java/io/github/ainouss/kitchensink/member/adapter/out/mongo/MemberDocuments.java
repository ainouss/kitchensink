package io.github.ainouss.kitchensink.member.adapter.out.mongo;

import java.util.List;

import com.mongodb.client.model.Collation;
import org.bson.Document;

import io.github.ainouss.kitchensink.member.domain.Member;

/**
 * The member document in the {@code members} collection. The migration worker writes the same fields.
 * See docs/architecture.md for the full shape.
 *
 * <p>Uses the MongoDB driver directly, without an object mapper, so the document holds exactly these fields and types.
 */
public final class MemberDocuments {

    public static final String COLLECTION = "members";
    public static final String ID = "_id";
    public static final String NAME = "name";
    public static final String EMAIL = "email";
    public static final String PHONE_NUMBER = "phoneNumber";
    public static final String SCHEMA_VERSION = "schemaVersion";
    public static final String QUALITY = "quality";
    public static final String SYNC = "sync";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** The list's sort order (R8): English rules, as the legacy's PostgreSQL sorts. */
    public static final Collation BY_NAME = Collation.builder().locale("en").build();

    /** The document's structure, checked by MongoDB. The business rules stay in the domain. */
    public static final Document JSON_SCHEMA = new Document("$jsonSchema", new Document()
            .append("bsonType", "object")
            .append("required", List.of(ID, NAME, EMAIL, PHONE_NUMBER, SCHEMA_VERSION))
            .append("additionalProperties", false)
            .append("properties", new Document()
                    .append(ID, new Document("bsonType", "long"))
                    .append(NAME, new Document("bsonType", "string"))
                    .append(EMAIL, new Document("bsonType", "string"))
                    .append(PHONE_NUMBER, new Document("bsonType", "string"))
                    .append(SCHEMA_VERSION, new Document("bsonType", "int"))
                    .append(QUALITY, new Document("bsonType", "array").append("items", new Document("bsonType", "string")))
                    .append(SYNC, new Document("bsonType", "object"))));

    private MemberDocuments() {
    }

    public static Document toDocument(Member member) {
        return new Document(ID, member.id())
                .append(NAME, member.name())
                .append(EMAIL, member.email())
                .append(PHONE_NUMBER, member.phoneNumber())
                .append(SCHEMA_VERSION, CURRENT_SCHEMA_VERSION);
    }

    public static Member toMember(Document document) {
        return new Member(document.get(ID, Number.class).longValue(), document.getString(NAME),
                document.getString(EMAIL), document.getString(PHONE_NUMBER));
    }
}
