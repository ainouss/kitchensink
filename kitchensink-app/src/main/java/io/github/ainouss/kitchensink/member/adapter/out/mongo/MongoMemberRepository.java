package io.github.ainouss.kitchensink.member.adapter.out.mongo;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.Sorts.ascending;
import static io.github.ainouss.kitchensink.member.adapter.out.mongo.MemberDocuments.BY_NAME;
import static io.github.ainouss.kitchensink.member.adapter.out.mongo.MemberDocuments.EMAIL;
import static io.github.ainouss.kitchensink.member.adapter.out.mongo.MemberDocuments.ID;
import static io.github.ainouss.kitchensink.member.adapter.out.mongo.MemberDocuments.NAME;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import io.github.ainouss.kitchensink.member.application.MemberRepository;
import io.github.ainouss.kitchensink.member.domain.EmailTakenException;
import io.github.ainouss.kitchensink.member.domain.Member;

/** Members in MongoDB. Lists are sorted by name then id, with the index's collation. */
@Repository
class MongoMemberRepository implements MemberRepository {

    private static final Bson BY_NAME_THEN_ID = ascending(NAME, ID);
    /** Batch size when streaming the full list: fast, with flat memory (R17). */
    private static final int STREAM_BATCH = 1_000;

    private final MongoCollection<Document> members;

    MongoMemberRepository(MongoTemplate mongo) {
        this.members = mongo.getCollection(MemberDocuments.COLLECTION);
    }

    @Override
    public void insert(Member member) {
        try {
            members.insertOne(MemberDocuments.toDocument(member));
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY && e.getError().getMessage().contains(EMAIL)) {
                throw new EmailTakenException(member.email());
            }
            throw e;
        }
    }

    @Override
    public Optional<Member> findById(long id) {
        return Optional.ofNullable(members.find(eq(ID, id)).first()).map(MemberDocuments::toMember);
    }

    @Override
    public boolean existsByEmail(String email) {
        return members.find(eq(EMAIL, email)).projection(include(ID)).limit(1).first() != null;
    }

    @Override
    public void forEachByName(Consumer<Member> action) {
        try (MongoCursor<Document> cursor = members.find().sort(BY_NAME_THEN_ID).collation(BY_NAME)
                .batchSize(STREAM_BATCH).cursor()) {
            cursor.forEachRemaining(document -> action.accept(MemberDocuments.toMember(document)));
        }
    }

    @Override
    public List<Member> page(int page, int size) {
        // toIntExact fails on overflow instead of giving a negative skip.
        return members.find().sort(BY_NAME_THEN_ID).collation(BY_NAME)
                .skip(Math.toIntExact((long) page * size)).limit(size)
                .map(MemberDocuments::toMember).into(new ArrayList<>(size));
    }

    @Override
    public long count() {
        return members.estimatedDocumentCount();
    }
}
