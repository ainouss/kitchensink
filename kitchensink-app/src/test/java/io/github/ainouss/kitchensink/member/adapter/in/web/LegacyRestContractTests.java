package io.github.ainouss.kitchensink.member.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.github.ainouss.kitchensink.TestMongo;

/** {@code /rest/members} answers as the legacy did (rules R6 to R13, R17, R18). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestMongo.class)
class LegacyRestContractTests {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String JANE = "{\"name\":\"Jane Doe\",\"email\":\"jane@mailinator.com\",\"phoneNumber\":\"2125551234\"}";

    @Value("${local.server.port}")
    int port;
    @Autowired
    MongoTemplate mongo;
    @Autowired
    JsonMapper json;

    @BeforeEach
    void onlyTheSeed() {
        mongo.getCollection("members").deleteMany(new Document("_id", new Document("$ne", 0L)));
    }

    HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/rest/members", "/kitchensink/rest/members"})
    void a_member_reads_with_its_fields_in_alphabetical_order(String base) throws Exception {
        HttpResponse<String> response = get(base + "/0");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(
                "{\"email\":\"john.smith@mailinator.com\",\"id\":0,\"name\":\"John Smith\",\"phoneNumber\":\"2125551212\"}");
    }

    @Test
    void an_unknown_or_non_numeric_id_is_404() throws Exception {
        assertThat(get("/rest/members/999999").statusCode()).isEqualTo(404);
        assertThat(get("/rest/members/999999").body()).isEmpty();
        assertThat(get("/rest/members/abc").statusCode()).isEqualTo(404);
        assertThat(get("/rest/members/abc").body()).isEmpty();
        assertThat(get("/rest/members/-1").body()).isEmpty();
        assertThat(get("/rest/members/99999999999999999999").statusCode()).isEqualTo(404);
    }

    @Test
    void a_registration_is_200_with_an_empty_body_and_then_listed() throws Exception {
        HttpResponse<String> created = post("/kitchensink/rest/members", JANE);

        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(created.body()).isEmpty();
        JsonNode list = json.readTree(get("/rest/members").body());
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("name").asString()).isEqualTo("Jane Doe");
    }

    @Test
    void a_taken_email_is_409_email_taken() throws Exception {
        post("/rest/members", JANE);

        HttpResponse<String> again = post("/rest/members", JANE);

        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(json.readTree(again.body()).get("email").asString()).isEqualTo("Email taken");
    }

    @Test
    void an_invalid_member_is_400_with_a_message_per_property_in_the_servers_language() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/rest/members")).header("Content-Type", "application/json").header("Accept-Language", "fr")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"J4ne\",\"email\":\"x\",\"phoneNumber\":\"12\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode fields = json.readTree(response.body());
        assertThat(fields.get("name").asString()).isEqualTo("must contain only letters and spaces");
        assertThat(fields.get("email").asString()).isEqualTo("must be a well-formed email address");
        assertThat(fields.get("phoneNumber").asString()).isNotBlank();
    }

    @Test
    void a_client_supplied_id_or_an_unreadable_body_is_400_error() throws Exception {
        HttpResponse<String> withId = post("/rest/members", "{\"id\":7," + JANE.substring(1));
        assertThat(withId.statusCode()).isEqualTo(400);
        assertThat(json.readTree(withId.body()).has("error")).isTrue();

        HttpResponse<String> notJson = post("/rest/members", "{not json");
        assertThat(notJson.statusCode()).isEqualTo(400);
        assertThat(json.readTree(notJson.body()).has("error")).isTrue();
    }

    @Test
    void the_full_list_is_streamed_in_order_whatever_its_size() throws Exception {
        List<Document> many = new ArrayList<>();
        for (int i = 1; i <= 5_000; i++) {
            many.add(new Document("_id", (long) i).append("name", "Member " + String.format("%05d", 5_001 - i))
                    .append("email", "m" + i + "@mailinator.com").append("phoneNumber", "2125550000")
                    .append("schemaVersion", 1));
        }
        mongo.getCollection("members").insertMany(many);

        JsonNode list = json.readTree(get("/rest/members").body());

        assertThat(list).hasSize(5_001);
        assertThat(list.get(0).get("name").asString()).isEqualTo("John Smith");
        assertThat(list.get(1).get("name").asString()).isEqualTo("Member 00001");
        assertThat(list.get(5_000).get("name").asString()).isEqualTo("Member 05000");
    }
}
