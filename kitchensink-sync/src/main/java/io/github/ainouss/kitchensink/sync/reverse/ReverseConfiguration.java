package io.github.ainouss.kitchensink.sync.reverse;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import io.github.ainouss.kitchensink.sync.SyncProperties;
import io.github.ainouss.kitchensink.sync.forward.ForwardSync;
import io.github.ainouss.kitchensink.sync.member.MemberRowMapper;

/** Wires the reverse stream, the sequence steps and the reverse stream's health check. */
@Configuration(proxyBeanMethods = false)
class ReverseConfiguration {

    @Bean
    ReverseSyncStatus reverseSyncStatus() {
        return new ReverseSyncStatus();
    }

    @Bean
    ReverseSync reverseSync(SyncProperties properties, MongoTemplate mongo, MemberRowMapper mapper,
                            ReverseSyncStatus status, ForwardSync forward) {
        return new ReverseSync(properties, mongo.getDb(), mapper, status, forward::isRunning);
    }

    @Bean
    Sequences sequences(SyncProperties properties, MongoTemplate mongo) {
        return new Sequences(properties.legacy(), mongo.getDb());
    }

    /** DOWN when the reverse stream has failed, so the worker gets restarted. */
    @Bean("reverseSyncHealthIndicator")
    HealthIndicator reverseSyncHealth(ReverseSyncStatus status) {
        return () -> status.state() == ReverseSyncStatus.State.FAILED
                ? Health.down().withDetail("state", status.state()).withDetail("failure", String.valueOf(status.failure()))
                .build()
                : Health.up().withDetail("state", status.state()).build();
    }
}
