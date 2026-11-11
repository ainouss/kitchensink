package io.github.ainouss.kitchensink.member.adapter.out.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.mongodb.MongoWriteException;
import jakarta.validation.ConstraintViolationException;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

import io.github.ainouss.kitchensink.TestMongo;
import io.github.ainouss.kitchensink.member.application.FindMembers;
import io.github.ainouss.kitchensink.member.application.RegisterMember;
import io.github.ainouss.kitchensink.member.domain.EmailTakenException;
import io.github.ainouss.kitchensink.member.domain.Member;
import io.github.ainouss.kitchensink.member.domain.NewMember;

/** Storage on a real MongoDB: the document, ids, indexes, validator and sort order. */
@SpringBootTest
@Import(TestMongo.class)
class MemberPersistenceTests {

    @Autowired
    MongoTemplate mongo;
    @Autowired
    MembersCollection collection;
    @Autowired
    RegisterMember register;
    @Autowired
    FindMembers find;

    @BeforeEach
    void emptyStore() {
        mongo.getDb().getCollection(MemberDocuments.COLLECTION).drop();
        mongo.getDb().getCollection(MongoMemberIds.COLLECTION).drop();
        collection.ensure();
    }

    static NewMember jane() {
        return new NewMember("Jane Doe", "jane@mailinator.com", "2125551234");
    }

    @Test
    void the_legacy_seed_is_there_on_an_empty_store_and_a_registration_comes_back_as_it_was_stored() {
        assertThat(find.byId(0)).contains(MembersCollection.LEGACY_SEED);

        Member jane = register.register(jane());

        assertThat(find.byId(jane.id())).contains(jane);
        Document stored = mongo.getCollection(MemberDocuments.COLLECTION).find(new Document("_id", jane.id())).first();
        assertThat(stored).containsOnlyKeys("_id", "name", "email", "phoneNumber", "schemaVersion");
        assertThat(stored.get("_id")).isInstanceOf(Long.class);
        assertThat(stored.get("phoneNumber")).isEqualTo("2125551234");
        assertThat(stored.get("schemaVersion")).isEqualTo(1);
    }

    @Test
    void ids_increase_and_never_reuse_a_carried_members_id() {
        mongo.getCollection(MemberDocuments.COLLECTION).insertOne(MemberDocuments.toDocument(
                new Member(1_000_100, "Carried Member", "carried@mailinator.com", "2125550000")));
        collection.ensure(); // like a restart after migration: the counter moves up to the largest id

        long first = register.register(jane()).id();
        long second = register.register(new NewMember("John Roe", "john@mailinator.com", "2125554321")).id();

        assertThat(first).isEqualTo(1_000_101);
        assertThat(second).isEqualTo(first + 1);
    }

    @Test
    void an_invalid_registration_is_refused_before_anything_is_written() {
        assertThatThrownBy(() -> register.register(new NewMember("J4ne", "jane", "12")))
                .isInstanceOf(ConstraintViolationException.class);
        assertThat(find.page(0, 10).totalElements()).isEqualTo(1); // the seed alone
    }

    @Test
    void a_taken_email_is_refused_case_sensitively() {
        register.register(jane());

        assertThatThrownBy(() -> register.register(jane())).isInstanceOf(EmailTakenException.class);
        // R4: emails are case-sensitive, so this is a different member.
        assertThat(register.register(new NewMember("Jane Doe", "JANE@mailinator.com", "2125551234")).email())
                .isEqualTo("JANE@mailinator.com");
    }

    @Test
    void two_registrations_racing_for_one_email_end_with_one_member_and_one_email_taken() throws Exception {
        int racers = 8;
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> attempt = () -> {
            start.await();
            try {
                return register.register(jane());
            } catch (EmailTakenException e) {
                return e;
            }
        };
        List<Future<Object>> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < racers; i++) {
                outcomes.add(pool.submit(attempt));
            }
            start.countDown();
        }
        List<Object> results = new ArrayList<>();
        for (Future<Object> outcome : outcomes) {
            results.add(outcome.get());
        }
        assertThat(results).filteredOn(Member.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(EmailTakenException.class::isInstance).hasSize(racers - 1);
    }

    @Test
    void the_list_follows_english_collation_then_id_not_byte_order() {
        for (String name : List.of("zoe", "Ørsted", "Zoë", "emma", "Émile")) {
            register.register(new NewMember(name, name.toLowerCase() + "@mailinator.com", "2125551234"));
        }
        List<String> names = new ArrayList<>();
        find.eachByName(member -> names.add(member.name()));

        // A plain byte sort would put upper case first and accents last.
        assertThat(names).containsExactly("Émile", "emma", "John Smith", "Ørsted", "zoe", "Zoë");
        assertThat(find.page(1, 2).content()).extracting(Member::name).containsExactly("John Smith", "Ørsted");
    }

    @Test
    void the_validator_refuses_a_document_that_breaks_the_structure() {
        assertThatThrownBy(() -> mongo.getCollection(MemberDocuments.COLLECTION).insertOne(
                new Document("_id", 42L).append("name", "No Email").append("phoneNumber", "2125551234")
                        .append("schemaVersion", 1)))
                .isInstanceOf(MongoWriteException.class)
                .hasMessageContaining("Document failed validation");
        assertThatThrownBy(() -> mongo.getCollection(MemberDocuments.COLLECTION).insertOne(
                MemberDocuments.toDocument(new Member(43, "A", "a@b", "2125551234")).append("_class", "Member")))
                .isInstanceOf(MongoWriteException.class);
    }

    @Test
    void a_carried_member_that_breaks_todays_rules_is_still_stored_and_read() {
        Document carried = MemberDocuments.toDocument(new Member(7, "Ada Lovelace 7", "a@@b.com", "212-555-1234"))
                .append("quality", List.of("NAME_HAS_DIGIT", "EMAIL_SYNTAX", "PHONE_FORMAT"))
                .append("sync", new Document("origin", "LEGACY").append("lsn", 123L));
        mongo.getCollection(MemberDocuments.COLLECTION).insertOne(carried);

        assertThat(find.byId(7)).map(Member::name).contains("Ada Lovelace 7");
    }

    @Test
    void ensuring_the_collection_twice_changes_nothing() {
        register.register(jane());
        collection.ensure();
        collection.ensure();

        List<String> indexes = mongo.getCollection(MemberDocuments.COLLECTION).listIndexes()
                .map(index -> index.getString("name")).into(new ArrayList<>());
        assertThat(indexes).containsExactlyInAnyOrder("_id_", "email_unique", "name_id");
        assertThat(find.page(0, 10).totalElements()).isEqualTo(2);
    }
}
