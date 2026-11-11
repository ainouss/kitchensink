package io.github.ainouss.kitchensink.sync.status;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.github.ainouss.kitchensink.sync.SyncTestStores;
import io.github.ainouss.kitchensink.sync.forward.ForwardSync;
import io.github.ainouss.kitchensink.sync.forward.ForwardSyncStatus;

/** Checks {@code /sync/status}, {@code /sync/forward/start|stop|drain} and the metrics in a running worker. */
@SpringBootTest(properties = {
        "kitchensink.sync.forward.enabled=false",
        "kitchensink.sync.forward.slot=ks11_api",
        "kitchensink.sync.forward.probe-interval=200ms",
        "kitchensink.sync.reconcile.scheduled=false",
        "management.endpoints.web.exposure.include=prometheus"})
@AutoConfigureMockMvc
class SyncStatusApiTests {

    private static MongoDatabase database;

    @Autowired
    MockMvc mvc;
    @Autowired
    ForwardSyncStatus status;
    @Autowired
    ForwardSync forward;

    @DynamicPropertySource
    static void stores(DynamicPropertyRegistry registry) throws Exception {
        SyncTestStores.resetLegacy();
        database = SyncTestStores.newMongoDatabase();
        registry.add("spring.mongodb.uri", () -> SyncTestStores.mongoUri(database.getName()));
        registry.add("kitchensink.sync.legacy.jdbc-url", SyncTestStores::legacyJdbcUrl);
        registry.add("kitchensink.sync.legacy.user", () -> SyncTestStores.SYNC_USER);
        registry.add("kitchensink.sync.legacy.password", () -> SyncTestStores.SYNC_PASSWORD);
    }

    @AfterAll
    static void dropTheSlot() throws Exception {
        SyncTestStores.dropSlot("ks11_api");
    }

    @Test
    void the_stream_is_started_watched_drained_and_stopped_through_the_api() throws Exception {
        mvc.perform(get("/sync/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.forward.state").value("STOPPED"))
                .andExpect(jsonPath("$.forward.legacyCurrentLsn").isString())
                .andExpect(jsonPath("$.forward.slotExists").value(false))
                .andExpect(jsonPath("$.direction.decidedBy").value("the environment"))
                .andExpect(jsonPath("$.forward.enabledAtStartup").value(false))
                .andExpect(jsonPath("$.reverse.enabledAtStartup").value(false));

        mvc.perform(post("/sync/forward/start")).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(60)).until(() -> status.snapshotRows() == 1
                && status.lagSeconds(System.currentTimeMillis()) != null);

        mvc.perform(get("/sync/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.forward.state").value("RUNNING"))
                .andExpect(jsonPath("$.forward.slotExists").value(true))
                .andExpect(jsonPath("$.forward.retainedWalBytes").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.forward.lagSeconds").isNumber())
                .andExpect(jsonPath("$.forward.counters.snapshotRows").value(1))
                .andExpect(jsonPath("$.direction.recorded").value("FORWARD"))
                .andExpect(jsonPath("$.direction.decidedBy").value("the last stream started"))
                .andExpect(jsonPath("$.forward.enabledAtStartup").value(true))
                .andExpect(jsonPath("$.reverse.enabledAtStartup").value(false));

        SyncTestStores.legacySql("INSERT INTO member VALUES (42, 'api@mailinator.com', 'Api Member', '2125550042')");
        mvc.perform(post("/sync/forward/drain")).andExpect(status().isOk())
                .andExpect(jsonPath("$.drained").value(true));
        mvc.perform(get("/sync/status")).andExpect(jsonPath("$.forward.counters.upserted").value(2));

        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andExpect(content().string(containsString("sync_forward_lag_seconds")))
                .andExpect(content().string(containsString("sync_forward_retained_wal_bytes")))
                .andExpect(content().string(containsString("sync_forward_changes_total{outcome=\"upserted\"}")));

        mvc.perform(post("/sync/forward/stop")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STOPPED"));
        mvc.perform(post("/sync/forward/drain")).andExpect(status().isConflict());
    }
}
