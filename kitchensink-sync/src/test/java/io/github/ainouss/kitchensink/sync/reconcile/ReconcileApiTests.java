package io.github.ainouss.kitchensink.sync.reconcile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.github.ainouss.kitchensink.sync.SyncTestStores;

/** Checks {@code POST /sync/reconcile} and {@code GET /sync/reconcile/latest} in a running worker. */
@SpringBootTest(properties = {"kitchensink.sync.forward.enabled=false", "kitchensink.sync.reconcile.scheduled=false"})
@AutoConfigureMockMvc
class ReconcileApiTests {

    private static MongoDatabase database;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void stores(DynamicPropertyRegistry registry) throws Exception {
        SyncTestStores.resetLegacy();
        database = SyncTestStores.newMongoDatabase();
        SyncTestStores.copyLegacyTo(database);
        registry.add("spring.mongodb.uri", () -> SyncTestStores.mongoUri(database.getName()));
        registry.add("kitchensink.sync.legacy.jdbc-url", SyncTestStores::legacyJdbcUrl);
        registry.add("kitchensink.sync.legacy.user", () -> SyncTestStores.SYNC_USER);
        registry.add("kitchensink.sync.legacy.password", () -> SyncTestStores.SYNC_PASSWORD);
    }

    @Test
    void a_run_on_demand_answers_its_report_and_the_latest_is_kept() throws Exception {
        mvc.perform(post("/sync/reconcile")).andExpect(status().isOk())
                .andExpect(jsonPath("$.green").value(true))
                .andExpect(jsonPath("$.legacyCount").value(1))
                .andExpect(jsonPath("$.counts.MISSING_IN_MONGODB").value(0))
                .andExpect(jsonPath("$.legacyDigest").isString());
        mvc.perform(get("/sync/reconcile/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.green").value(true))
                .andExpect(jsonPath("$.id").isString());
    }
}
