package io.github.ainouss.kitchensink.sync.reconcile;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import io.github.ainouss.kitchensink.sync.SyncProperties;
import io.github.ainouss.kitchensink.sync.member.MemberRowMapper;

/** The reconciler, and its schedule when {@code kitchensink.sync.reconcile.scheduled} is on. */
@Configuration(proxyBeanMethods = false)
class ReconcileConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ReconcileConfiguration.class);

    @Bean
    Reconciler reconciler(SyncProperties properties, MongoTemplate mongo, MemberRowMapper mapper) {
        return new Reconciler(properties, mongo, mapper, Clock.systemUTC());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBooleanProperty("kitchensink.sync.reconcile.scheduled")
    static class Scheduled implements SchedulingConfigurer {

        private final Reconciler reconciler;
        private final SyncProperties properties;

        Scheduled(Reconciler reconciler, SyncProperties properties) {
            this.reconciler = reconciler;
            this.properties = properties;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar tasks) {
            // First run after one interval, so it does not overlap the initial snapshot.
            tasks.addFixedDelayTask(new FixedDelayTask(this::run, properties.reconcile().interval(),
                    properties.reconcile().interval()));
        }

        private void run() {
            try {
                reconciler.reconcile();
            } catch (Reconciler.AlreadyRunningException e) {
                log.info("Scheduled reconciliation skipped: one is already running");
            } catch (RuntimeException e) {
                log.error("Scheduled reconciliation failed: {}", e.getMessage(), e);
            }
        }
    }
}
