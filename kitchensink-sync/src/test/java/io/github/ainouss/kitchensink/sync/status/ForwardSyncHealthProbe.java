package io.github.ainouss.kitchensink.sync.status;

import io.github.ainouss.kitchensink.sync.forward.ForwardSyncStatus;

/** Lets tests in other packages read the {@link ForwardSyncHealth} status code for a stream state. */
public final class ForwardSyncHealthProbe {

    private ForwardSyncHealthProbe() {
    }

    public static String health(ForwardSyncStatus status) {
        return new ForwardSyncHealth(status).health().getStatus().getCode();
    }
}
