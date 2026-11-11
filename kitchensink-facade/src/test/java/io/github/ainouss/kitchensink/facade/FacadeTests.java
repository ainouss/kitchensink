package io.github.ainouss.kitchensink.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.ainouss.kitchensink.facade.StubUpstream.Answer;
import io.github.ainouss.kitchensink.facade.control.Control.Reads;
import io.github.ainouss.kitchensink.facade.control.Control.Writes;
import io.github.ainouss.kitchensink.facade.control.ControlStore;
import io.github.ainouss.kitchensink.facade.shadow.DualRun;

/** The facade between two stub sides: routing, shadow compares, paused writes, the admin token. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "kitchensink.facade.admin-token=test-token",
        "kitchensink.facade.control-refresh=1h",
        "kitchensink.facade.shadow.timeout=1s"})
@Import(TestMongo.class)
class FacadeTests {

    static final StubUpstream LEGACY = new StubUpstream();
    static final StubUpstream NEW = new StubUpstream();
    private static final String MEMBER = "/kitchensink/rest/members/7";
    private static final String MEMBER_JSON =
            "{\"email\":\"ada@mailinator.com\",\"id\":7,\"name\":\"Ada Lovelace\",\"phoneNumber\":\"2125551234\"}";

    @DynamicPropertySource
    static void sides(DynamicPropertyRegistry registry) {
        registry.add("kitchensink.facade.legacy-url", LEGACY::url);
        registry.add("kitchensink.facade.new-url", NEW::url);
    }

    @AfterAll
    static void stopSides() {
        LEGACY.close();
        NEW.close();
    }

    @Value("${local.server.port}")
    int port;
    @Autowired
    ControlStore control;
    @Autowired
    DualRun dualRun;
    @Autowired
    MongoTemplate mongo;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void freshSides() {
        LEGACY.reset();
        NEW.reset();
        mongo.getCollection(DualRun.MISMATCHES).deleteMany(new Document());
        control.set(Reads.LEGACY, Writes.LEGACY, "test");
    }

    @Test
    void reads_and_writes_go_to_the_side_the_control_names() throws Exception {
        LEGACY.answer("GET", MEMBER, Answer.json(200, MEMBER_JSON)).answer("POST", "/kitchensink/rest/members",
                Answer.json(200, ""));
        NEW.answer("GET", MEMBER, Answer.json(200, MEMBER_JSON.replace("Ada", "New Ada")))
                .answer("POST", "/kitchensink/rest/members", Answer.json(200, ""));

        assertThat(get(MEMBER).body()).contains("\"Ada Lovelace\"");
        assertThat(post("/kitchensink/rest/members", "{\"name\":\"Grace\"}").statusCode()).isEqualTo(200);
        assertThat(LEGACY.received()).extracting(StubUpstream.Received::method).containsExactly("GET", "POST");
        assertThat(LEGACY.received().get(1).body()).isEqualTo("{\"name\":\"Grace\"}");
        assertThat(NEW.received()).isEmpty();

        control.set(Reads.NEW, Writes.NEW, "test");
        assertThat(get(MEMBER).body()).contains("\"New Ada Lovelace\"");
        post("/kitchensink/rest/members", "{\"name\":\"Hedy\"}");
        assertThat(NEW.received()).extracting(StubUpstream.Received::method).containsExactly("GET", "POST");
        assertThat(LEGACY.received()).hasSize(2);
    }

    @Test
    void in_shadow_the_primary_answers_and_the_other_side_is_compared_in_the_background() throws Exception {
        control.set(Reads.SHADOW_LEGACY, Writes.LEGACY, "test");
        LEGACY.answer("GET", MEMBER, Answer.json(200, MEMBER_JSON));
        // Same member, other key order and other spacing: the same JSON.
        NEW.answer("GET", MEMBER, Answer.json(200,
                "{ \"id\": 7, \"name\": \"Ada Lovelace\", \"email\": \"ada@mailinator.com\", \"phoneNumber\": \"2125551234\" }"));
        long matched = total(DualRun.Outcome.MATCHED);

        HttpResponse<String> response = get(MEMBER);

        assertThat(response.body()).isEqualTo(MEMBER_JSON);
        await().atMost(Duration.ofSeconds(5)).until(() -> total(DualRun.Outcome.MATCHED) == matched + 1);
        assertThat(NEW.received()).hasSize(1);
        assertThat(mongo.getCollection(DualRun.MISMATCHES).countDocuments()).isZero();

        control.set(Reads.SHADOW_NEW, Writes.LEGACY, "test");
        NEW.answer("GET", MEMBER, Answer.json(200, MEMBER_JSON.replace("Ada Lovelace", "Answered By New")));
        assertThat(get(MEMBER).body()).contains("Answered By New");
        await().atMost(Duration.ofSeconds(5)).until(() -> LEGACY.received().size() == 2);
    }

    @Test
    void a_compared_read_asks_both_sides_for_identity_encoding_so_compression_cannot_fake_a_mismatch()
            throws Exception {
        control.set(Reads.SHADOW_LEGACY, Writes.LEGACY, "test");
        LEGACY.answer("GET", MEMBER, Answer.json(200, MEMBER_JSON));
        NEW.answer("GET", MEMBER, Answer.json(200, MEMBER_JSON));
        long matched = total(DualRun.Outcome.MATCHED);

        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(MEMBER))
                .header("Accept-Encoding", "gzip, deflate, br").GET().build());

        assertThat(response.body()).isEqualTo(MEMBER_JSON);
        await().atMost(Duration.ofSeconds(5)).until(() -> total(DualRun.Outcome.MATCHED) == matched + 1);
        assertThat(LEGACY.received().getFirst().header("Accept-Encoding")).isNull();
        assertThat(NEW.received().getFirst().header("Accept-Encoding")).isNull();

        // Outside a compare the client's encoding goes through unchanged.
        control.set(Reads.LEGACY, Writes.LEGACY, "test");
        send(HttpRequest.newBuilder(uri(MEMBER)).header("Accept-Encoding", "gzip").GET().build());
        assertThat(LEGACY.received().getLast().header("Accept-Encoding")).isEqualTo("gzip");
    }

    @Test
    void a_mismatch_is_kept_with_the_request_both_answers_and_the_diff() throws Exception {
        control.set(Reads.SHADOW_LEGACY, Writes.LEGACY, "test");
        LEGACY.answer("GET", MEMBER, Answer.json(200, MEMBER_JSON));
        NEW.answer("GET", MEMBER, Answer.json(200, MEMBER_JSON.replace("ada@mailinator.com", "ADA@mailinator.com")));
        long mismatched = total(DualRun.Outcome.MISMATCHED);

        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(MEMBER)).header("X-Correlation-Id", "dual-1")
                .GET().build());

        assertThat(response.body()).isEqualTo(MEMBER_JSON);
        await().atMost(Duration.ofSeconds(5)).until(() -> total(DualRun.Outcome.MISMATCHED) == mismatched + 1);
        Document stored = mongo.getCollection(DualRun.MISMATCHES).find().first();
        assertThat(stored.getString("correlationId")).isEqualTo("dual-1");
        assertThat(stored.get("request", Document.class).getString("path")).isEqualTo(MEMBER);
        assertThat(stored.get("primary", Document.class).getString("side")).isEqualTo("LEGACY");
        assertThat(stored.get("shadow", Document.class).getInteger("status")).isEqualTo(200);
        assertThat(stored.getList("diff", Document.class)).containsExactly(new Document("path", "$.email")
                .append("legacy", "\"ada@mailinator.com\"").append("new", "\"ADA@mailinator.com\""));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> routes = (Map<String, Map<String, Long>>) (Map<?, ?>) dualRun.stats().get("routes");
        assertThat(routes).containsKey("/kitchensink/rest/members/{id}");
    }

    @Test
    void a_shadow_side_that_fails_or_is_slow_changes_nothing_for_the_user() throws Exception {
        control.set(Reads.SHADOW_LEGACY, Writes.LEGACY, "test");
        LEGACY.answer("GET", MEMBER, Answer.json(200, MEMBER_JSON));
        NEW.answer("GET", MEMBER, Answer.json(200, MEMBER_JSON).after(Duration.ofSeconds(3)));
        long failed = total(DualRun.Outcome.SHADOW_FAILED);

        long start = System.nanoTime();
        HttpResponse<String> response = get(MEMBER);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(MEMBER_JSON);
        assertThat(took).as("the user never waits for the shadow").isLessThan(Duration.ofSeconds(1));
        // Past the shadow's 1 s timeout: counted as failed, nothing stored.
        await().atMost(Duration.ofSeconds(5)).until(() -> total(DualRun.Outcome.SHADOW_FAILED) == failed + 1);
        assertThat(mongo.getCollection(DualRun.MISMATCHES).countDocuments()).isZero();
    }

    @Test
    void an_accepted_difference_is_counted_with_its_rule_not_stored() throws Exception {
        control.set(Reads.SHADOW_NEW, Writes.LEGACY, "test");
        // Rule R17: the legacy's full list fails at volume; the new one streams it.
        LEGACY.answer("GET", "/kitchensink/rest/members", Answer.json(500, "{\"error\":\"java.lang.OutOfMemoryError\"}"));
        NEW.answer("GET", "/kitchensink/rest/members", Answer.json(200, "[" + MEMBER_JSON + "]"));
        long accepted = total(DualRun.Outcome.ACCEPTED);

        assertThat(get("/kitchensink/rest/members").statusCode()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(5)).until(() -> total(DualRun.Outcome.ACCEPTED) == accepted + 1);
        assertThat(mongo.getCollection(DualRun.MISMATCHES).countDocuments()).isZero();
    }

    @Test
    void paused_writes_get_503_with_retry_after_and_reach_no_side_while_reads_go_on() throws Exception {
        control.set(Reads.LEGACY, Writes.PAUSED, "test");
        LEGACY.answer("GET", MEMBER, Answer.json(200, MEMBER_JSON));

        HttpResponse<String> write = post("/kitchensink/rest/members", "{\"name\":\"Ada\"}");

        assertThat(write.statusCode()).isEqualTo(503);
        assertThat(write.headers().firstValue("Retry-After")).hasValue("5");
        assertThat(write.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(write.body()).contains("urn:kitchensink:problem:writes-paused");
        assertThat(LEGACY.received()).isEmpty();
        assertThat(NEW.received()).isEmpty();
        assertThat(get(MEMBER).statusCode()).isEqualTo(200);
    }

    @Test
    void the_control_changes_only_with_the_admin_token_and_is_kept_in_mongodb() throws Exception {
        String change = "{\"reads\":\"SHADOW_LEGACY\",\"writes\":\"LEGACY\",\"by\":\"ks cutover\"}";

        assertThat(put("/facade/control", change, null).statusCode()).isEqualTo(401);
        assertThat(put("/facade/control", change, "wrong").statusCode()).isEqualTo(401);
        HttpResponse<String> changed = put("/facade/control", change, "test-token");

        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(changed.body()).contains("\"reads\":\"SHADOW_LEGACY\"").contains("\"updatedBy\":\"ks cutover\"");
        Document stored = mongo.getCollection(ControlStore.COLLECTION).find(new Document("_id", "members")).first();
        assertThat(stored.getString("reads")).isEqualTo("SHADOW_LEGACY");
        assertThat(get("/facade/control").body()).contains("\"writes\":\"LEGACY\"");
    }

    @Test
    void the_correlation_id_goes_to_both_sides_and_back_and_redirects_point_at_the_facade() throws Exception {
        control.set(Reads.SHADOW_LEGACY, Writes.LEGACY, "test");
        LEGACY.answer("GET", MEMBER, Answer.json(200, MEMBER_JSON));
        NEW.answer("GET", MEMBER, Answer.json(200, MEMBER_JSON));

        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(MEMBER)).header("X-Correlation-Id", "abc-123")
                .GET().build());

        assertThat(response.headers().firstValue("X-Correlation-Id")).hasValue("abc-123");
        assertThat(LEGACY.received().getFirst().header("X-Correlation-Id")).isEqualTo("abc-123");
        await().atMost(Duration.ofSeconds(5)).until(() -> !NEW.received().isEmpty());
        assertThat(NEW.received().getFirst().header("X-Correlation-Id")).isEqualTo("abc-123");
        assertThat(get(MEMBER).headers().firstValue("X-Correlation-Id")).isPresent();

        control.set(Reads.LEGACY, Writes.LEGACY, "test");
        LEGACY.answer("POST", "/kitchensink/index.jsf", new Answer(302, null, "", Duration.ZERO,
                Map.of("Location", LEGACY.url() + "/kitchensink/index.jsf")));
        HttpResponse<String> redirect = post("/kitchensink/index.jsf", "name=Ada");
        assertThat(redirect.headers().firstValue("Location")).hasValue("/kitchensink/index.jsf");
    }

    private long total(DualRun.Outcome outcome) {
        @SuppressWarnings("unchecked")
        Map<DualRun.Outcome, Long> totals = (Map<DualRun.Outcome, Long>) dualRun.stats().get("totals");
        return totals.get(outcome);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpResponse<String> put(String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return send(builder.build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
