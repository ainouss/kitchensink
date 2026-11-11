package io.github.ainouss.kitchensink.sync.status;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import io.github.ainouss.kitchensink.sync.forward.ForwardSyncStatus;

/**
 * Health of the forward stream: DOWN when it has failed, so the worker gets restarted and resumes from its stored
 * offset. A stream stopped on purpose is still UP.
 */
@Component("forwardSyncHealthIndicator") // shown as "forwardSync" in /actuator/health
class ForwardSyncHealth implements HealthIndicator {

    private final ForwardSyncStatus status;

    ForwardSyncHealth(ForwardSyncStatus status) {
        this.status = status;
    }

    @Override
    public Health health() {
        ForwardSyncStatus.State state = status.state();
        if (state == ForwardSyncStatus.State.FAILED) {
            return Health.down().withDetail("state", state).withDetail("failure", String.valueOf(status.failure()))
                    .build();
        }
        return Health.up().withDetail("state", state).build();
    }
}
