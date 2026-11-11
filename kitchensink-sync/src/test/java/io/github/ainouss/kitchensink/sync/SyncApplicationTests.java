package io.github.ainouss.kitchensink.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.ainouss.kitchensink.sync.forward.ForwardSync;
import io.github.ainouss.kitchensink.sync.forward.ForwardSyncStatus;
import io.github.ainouss.kitchensink.sync.reverse.ReverseSync;

/**
 * Checks the worker's context: settings bind, and the right stream starts with it.
 * Before any stream has run, the settings decide. After that, the recorded direction decides.
 */
@SpringBootTest(properties = {
        "kitchensink.sync.forward.enabled=false",
        "kitchensink.sync.legacy.jdbc-url=jdbc:postgresql://legacy-db:5432/kitchensink"})
class SyncApplicationTests {

    private static MongoDatabase database;

    @DynamicPropertySource
    static void store(DynamicPropertyRegistry registry) {
        database = SyncTestStores.newMongoDatabase();
        registry.add("spring.mongodb.uri", () -> SyncTestStores.mongoUri(database.getName()));
    }

    @Autowired
    ApplicationContext context;
    @Autowired
    SyncProperties properties;

    @Test
    void starts_with_the_forward_stream_off_when_disabled_but_ready_to_be_started() {
        ForwardSync forward = context.getBean(ForwardSync.class);
        assertThat(forward.isAutoStartup()).isFalse();
        assertThat(forward.isRunning()).isFalse();
        assertThat(context.getBean(ForwardSyncStatus.class).state()).isEqualTo(ForwardSyncStatus.State.STOPPED);
        assertThat(context.getBean(ReverseSync.class).isAutoStartup()).isFalse();
    }

    @Test
    void after_a_switch_the_worker_starts_the_recorded_direction_whatever_its_environment_says() {
        SyncDirection direction = context.getBean(SyncDirection.class);
        ForwardSync forward = context.getBean(ForwardSync.class);
        ReverseSync reverse = context.getBean(ReverseSync.class);
        try {
            direction.record(SyncDirection.Direction.REVERSE);
            assertThat(forward.isAutoStartup()).as("the cutover's direction").isFalse();
            assertThat(reverse.isAutoStartup()).isTrue();

            direction.record(SyncDirection.Direction.FORWARD);
            assertThat(forward.isAutoStartup()).as("a rollback's, though this environment disables it").isTrue();
            assertThat(reverse.isAutoStartup()).isFalse();
        } finally {
            database.getCollection(SyncDirection.COLLECTION).deleteOne(new Document("_id", "direction"));
        }
    }

    @Test
    void the_legacy_database_is_read_from_its_jdbc_url() {
        assertThat(properties.legacy().host()).isEqualTo("legacy-db");
        assertThat(properties.legacy().port()).isEqualTo(5432);
        assertThat(properties.legacy().database()).isEqualTo("kitchensink");
        assertThat(properties.forward().batchSize()).isEqualTo(2048);
    }
}
