package io.github.ainouss.kitchensink.member.adapter.in.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.github.ainouss.kitchensink.TestMongo;

/** The versioned API: its status codes, paging limits and problem details. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestMongo.class)
class MemberApiTests {

    private static final String PROBLEM = "application/problem+json";

    @Autowired
    MockMvc mvc;
    @Autowired
    MongoTemplate mongo;

    @BeforeEach
    void onlyTheSeed() {
        mongo.getCollection("members").deleteMany(new Document("_id", new Document("$ne", 0L)));
    }

    @Test
    void a_registration_is_201_with_the_members_address_and_the_member() throws Exception {
        String location = mvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ada Lovelace\",\"email\":\"ada@mailinator.com\",\"phoneNumber\":\"2125551234\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("http://localhost/api/v1/members/")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.email").value("ada@mailinator.com"))
                .andExpect(jsonPath("$.phoneNumber").value("2125551234"))
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get(location)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Ada Lovelace"));
    }

    @Test
    void a_member_breaking_the_rules_is_a_400_problem_listing_every_field_in_the_clients_language() throws Exception {
        mvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON).header("Accept-Language", "fr")
                        .content("{\"name\":\"J4ne\",\"email\":\"jane\",\"phoneNumber\":\"12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("urn:kitchensink:problem:invalid-member"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[*].field", contains("email", "name", "phoneNumber")))
                .andExpect(jsonPath("$.errors[1].message").value("ne doit contenir que des lettres et des espaces"));

        mvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("email", "email", "name", "phoneNumber")));
    }

    @Test
    void what_a_lenient_binder_would_drop_silently_is_refused() throws Exception {
        mvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":5,\"name\":\"Ada\",\"email\":\"ada@mailinator.com\",\"phoneNumber\":2125551234,"
                                + "\"nickname\":\"Countess\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.errors[*].field", contains("id", "nickname", "phoneNumber")))
                .andExpect(jsonPath("$.errors[0].message").value("is assigned by the server"))
                .andExpect(jsonPath("$.errors[2].message").value("must be a string"));

        mvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:kitchensink:problem:bad-request"));
        mvc.perform(post("/api/v1/members").contentType(MediaType.TEXT_PLAIN).content("Ada"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    @Test
    void a_taken_email_is_a_409_problem() throws Exception {
        mvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"John Smith\",\"email\":\"john.smith@mailinator.com\",\"phoneNumber\":\"2125551212\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("urn:kitchensink:problem:email-taken"))
                .andExpect(jsonPath("$.title").value("Email taken"));
    }

    @Test
    void a_member_by_id_is_200_an_unknown_one_a_404_problem_and_a_non_number_a_400() throws Exception {
        mvc.perform(get("/api/v1/members/0")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(0))
                .andExpect(jsonPath("$.name").value("John Smith"));
        mvc.perform(get("/api/v1/members/987654321")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("urn:kitchensink:problem:member-not-found"))
                .andExpect(jsonPath("$.id").value(987654321));
        mvc.perform(get("/api/v1/members/abc")).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    @Test
    void the_list_is_paged_by_name_then_id_within_its_bounds() throws Exception {
        List<Document> many = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            many.add(new Document("_id", (long) i).append("name", "Member " + (char) ('A' + (i % 26)))
                    .append("email", "m" + i + "@mailinator.com").append("phoneNumber", "2125550000")
                    .append("schemaVersion", 1));
        }
        mongo.getCollection("members").insertMany(many);

        mvc.perform(get("/api/v1/members")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(25)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalElements").value(121))
                .andExpect(jsonPath("$.totalPages").value(5))
                .andExpect(jsonPath("$.content[0].name").value("John Smith"))
                .andExpect(jsonPath("$.content[1].name").value("Member A"))
                .andExpect(jsonPath("$.content[1].id").value(26))
                .andExpect(jsonPath("$.content[2].id").value(52));
        mvc.perform(get("/api/v1/members?size=100&page=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(21)));
        mvc.perform(get("/api/v1/members?page=99")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(121));

        for (String outOfBounds : List.of("size=101", "size=0", "page=-1", "page=abc", "page=100000000&size=100")) {
            mvc.perform(get("/api/v1/members?" + outOfBounds)).andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                    .andExpect(jsonPath("$.type").value("urn:kitchensink:problem:bad-request"));
        }
    }

    @Test
    void the_legacy_contract_keeps_its_own_errors() throws Exception {
        mvc.perform(get("/rest/members/987654321")).andExpect(status().isNotFound());
        mvc.perform(post("/rest/members").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(header().string("Content-Type", endsWith("json")));
    }
}
