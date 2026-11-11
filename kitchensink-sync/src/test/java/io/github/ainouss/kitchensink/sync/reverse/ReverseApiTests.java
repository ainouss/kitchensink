package io.github.ainouss.kitchensink.sync.reverse;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.github.ainouss.kitchensink.sync.SyncTestStores;

/** Checks the reverse stream and sequence endpoints in a running worker. */
@SpringBootTest(properties = {
        "kitchensink.sync.forward.enabled=false",
        "kitchensink.sync.forward.slot=ks13_api",
        "kitchensink.sync.reverse.probe-interval=200ms",
        "kitchensink.sync.reconcile.scheduled=false"})
@AutoConfigureMockMvc
class ReverseApiTests {

    private static MongoDatabase database;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void stores(DynamicPropertyRegistry registry) throws Exception {
        SyncTestStores.resetLegacy();
        database = SyncTestStores.newMongoDatabase();
        registry.add("spring.mongodb.uri", () -> SyncTestStores.mongoUri(database.getName()));
        registry.add("kitchensink.sync.legacy.jdbc-url", SyncTestStores::legacyJdbcUrl);
        registry.add("kitchensink.sync.legacy.user", () -> SyncTestStores.SYNC_USER);
        registry.add("kitchensink.sync.legacy.password", () -> SyncTestStores.SYNC_PASSWORD);
    }

    @Test
    void the_switchs_steps_through_the_api() throws Exception {
        mvc.perform(post("/sync/target-sequence")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mongodbNextIdAbove").value(greaterThan(0)));
        mvc.perform(post("/sync/reverse/start").param("from", "now")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"));
        mvc.perform(post("/sync/forward/start")).andExpect(status().isConflict());

        database.getCollection("members").insertOne(new Document("_id", 9000L).append("name", "Api Member")
                .append("email", "api13@mailinator.com").append("phoneNumber", "2125550000").append("schemaVersion", 1));
        mvc.perform(post("/sync/reverse/drain")).andExpect(status().isOk())
                .andExpect(jsonPath("$.drained").value(true));
        mvc.perform(get("/sync/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reverse.state").value("RUNNING"))
                .andExpect(jsonPath("$.reverse.counters.upserted").value(1))
                .andExpect(jsonPath("$.forward.state").value("STOPPED"));

        mvc.perform(post("/sync/reverse/stop")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STOPPED"));
        mvc.perform(post("/sync/legacy-sequence")).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberSeq").value(greaterThan(9000)));
    }
}
