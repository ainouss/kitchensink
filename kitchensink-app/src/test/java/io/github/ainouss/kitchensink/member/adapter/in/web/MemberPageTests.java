package io.github.ainouss.kitchensink.member.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

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
import org.springframework.test.web.servlet.MockMvc;

import io.github.ainouss.kitchensink.TestMongo;

/** The registration page (rules R14, R16, R17). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestMongo.class)
class MemberPageTests {

    @Autowired
    MockMvc mvc;
    @Autowired
    MongoTemplate mongo;

    @BeforeEach
    void onlyTheSeed() {
        mongo.getCollection("members").deleteMany(new Document("_id", new Document("$ne", 0L)));
    }

    @Test
    void the_page_shows_the_form_and_the_members_at_both_addresses() throws Exception {
        for (String path : List.of("/", "/kitchensink/index.jsf")) {
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string(allOf(
                    containsString("Member Registration"), containsString("John Smith"),
                    containsString("Page 1 of 1, 1 members"))));
        }
        mvc.perform(get("/kitchensink/index.jsf"))
                .andExpect(content().string(containsString("href=\"/kitchensink/rest/members/0\"")));
    }

    @Test
    void a_registration_redirects_shows_registered_and_lists_the_member() throws Exception {
        mvc.perform(post("/").param("name", "Jane Doe").param("email", "jane@mailinator.com")
                        .param("phoneNumber", "2125551234"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("registered", true));

        mvc.perform(get("/").flashAttr("registered", true)).andExpect(content().string(allOf(
                containsString("Registered!"), containsString("Jane Doe"))));
    }

    @Test
    void a_field_error_keeps_the_values_and_shows_the_message_beside_the_field() throws Exception {
        mvc.perform(post("/").param("name", "J4ne").param("email", "jane@mailinator.com").param("phoneNumber", "12"))
                .andExpect(status().isOk()).andExpect(content().string(allOf(
                        containsString("must contain only letters and spaces"),
                        containsString("size must be between 10 and 12"),
                        containsString("value=\"J4ne\""), containsString("value=\"jane@mailinator.com\""),
                        not(containsString("Registered!")))));
    }

    @Test
    void registering_works_at_every_address_the_page_is_served_at() throws Exception {
        // The form posts back to the address the page was opened at.
        int n = 0;
        for (String path : List.of("/kitchensink", "/kitchensink/", "/kitchensink/index.html", "/index.html",
                "/index.jsf")) {
            String expectedRedirect = path.startsWith("/kitchensink") ? "/kitchensink/" : "/";
            mvc.perform(post(path).param("name", "Jane Doe").param("email", "jane" + (++n) + "@mailinator.com")
                            .param("phoneNumber", "2125551234"))
                    .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(expectedRedirect));
        }
    }

    @Test
    void a_taken_email_is_a_registration_failure() throws Exception {
        mvc.perform(post("/").param("name", "John Smith").param("email", "john.smith@mailinator.com")
                        .param("phoneNumber", "2125551212"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Registration failed: Email taken")));
    }

    @Test
    void the_page_and_its_messages_follow_the_browsers_language() throws Exception {
        mvc.perform(post("/").header("Accept-Language", "fr").param("name", "J4ne").param("email", "x")
                        .param("phoneNumber", "2125551234"))
                .andExpect(content().string(allOf(containsString("Inscription des Membres"),
                        containsString("ne doit contenir que des lettres et des espaces"))));
        mvc.perform(get("/").header("Accept-Language", "es"))
                .andExpect(content().string(allOf(containsString("Registro de Usuarios"),
                        containsString("Miembros"))));
        mvc.perform(get("/").header("Accept-Language", "de"))
                .andExpect(content().string(containsString("Member Registration")));
    }

    @Test
    void the_table_is_paged_25_at_a_time() throws Exception {
        List<Document> many = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            many.add(new Document("_id", (long) i).append("name", "Member " + String.format("%02d", i))
                    .append("email", "m" + i + "@mailinator.com").append("phoneNumber", "2125550000")
                    .append("schemaVersion", 1));
        }
        mongo.getCollection("members").insertMany(many);

        mvc.perform(get("/")).andExpect(content().string(allOf(containsString("Page 1 of 2, 31 members"),
                containsString("Member 24"), not(containsString("Member 25")), containsString("?page=1"))));
        mvc.perform(get("/?page=1")).andExpect(content().string(allOf(containsString("Page 2 of 2, 31 members"),
                containsString("Member 30"), not(containsString("Member 24")))));
    }

    @Test
    void a_page_out_of_range_shows_the_nearest_page_instead_of_failing() throws Exception {
        List<Document> many = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            many.add(new Document("_id", (long) i).append("name", "Member " + String.format("%02d", i))
                    .append("email", "m" + i + "@mailinator.com").append("phoneNumber", "2125550000")
                    .append("schemaVersion", 1));
        }
        mongo.getCollection("members").insertMany(many);

        // Any page past the end, even a huge one, shows the last page.
        for (String page : List.of("2", "7", "100000000", "2147483647")) {
            mvc.perform(get("/?page=" + page)).andExpect(status().isOk()).andExpect(content().string(allOf(
                    containsString("Page 2 of 2, 31 members"), containsString("Member 30"),
                    not(containsString("?page=2")))));
        }
        mvc.perform(get("/?page=-3")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Page 1 of 2, 31 members")));
    }
}
