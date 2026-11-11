package io.github.ainouss.kitchensink.sync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The migration worker. Run a single instance while both databases are in use. See docs/migration.md.
 */
@SpringBootApplication
@EnableConfigurationProperties(SyncProperties.class)
@EnableScheduling
public class SyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(SyncApplication.class, args);
    }
}
