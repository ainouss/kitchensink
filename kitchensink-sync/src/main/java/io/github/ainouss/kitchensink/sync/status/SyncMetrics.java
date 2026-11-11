package io.github.ainouss.kitchensink.sync.status;

import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.ainouss.kitchensink.sync.forward.ForwardSync;
import io.github.ainouss.kitchensink.sync.forward.ForwardSyncStatus;
import io.github.ainouss.kitchensink.sync.forward.LegacyWal;
import io.github.ainouss.kitchensink.sync.reconcile.Reconciler;
import io.github.ainouss.kitchensink.sync.reconcile.ReconciliationReport;

/**
 * Sync status as Prometheus metrics. The key alerts are {@code sync_forward_retained_wal_bytes} (a stuck slot fills
 * the legacy disk) and {@code sync_forward_lag_seconds}. Values that need a query are refreshed every 15 seconds.
 */
@Component
class SyncMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(SyncMetrics.class);

    private final ForwardSync forward;
    private final ForwardSyncStatus status;
    private final Reconciler reconciler;
    private final AtomicReference<ReconciliationReport> lastReport = new AtomicReference<>();

    SyncMetrics(ForwardSync forward, ForwardSyncStatus status, Reconciler reconciler) {
        this.forward = forward;
        this.status = status;
        this.reconciler = reconciler;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("sync.forward.running", status, s -> s.state() == ForwardSyncStatus.State.RUNNING ? 1 : 0)
                .description("1 while the forward stream runs").register(registry);
        Gauge.builder("sync.forward.lag.seconds", status, s -> {
            Double lag = s.lagSeconds(System.currentTimeMillis());
            return lag == null ? Double.NaN : lag;
        }).description("End-to-end lag of the forward stream (probe written in the legacy to applied in MongoDB)")
                .baseUnit("seconds").register(registry);
        Gauge.builder("sync.forward.retained.wal.bytes", status, s -> {
            LegacyWal.SlotState slot = s.slot();
            return slot == null ? Double.NaN : slot.retainedBytes();
        }).description("WAL the legacy keeps for the slot").baseUnit("bytes").register(registry);
        counter(registry, "upserted", ForwardSyncStatus::upserted);
        counter(registry, "deleted", ForwardSyncStatus::deleted);
        counter(registry, "skipped", ForwardSyncStatus::skipped);
        counter(registry, "quarantined", ForwardSyncStatus::quarantined);
        FunctionCounter.builder("sync.forward.snapshot.rows", status, ForwardSyncStatus::snapshotRows)
                .register(registry);
        Gauge.builder("sync.reconcile.green", lastReport, r -> r.get() == null ? Double.NaN : r.get().green() ? 1 : 0)
                .description("1 when the last reconciliation was green").register(registry);
        Gauge.builder("sync.reconcile.differences", lastReport, r -> r.get() == null ? Double.NaN
                : r.get().count(ReconciliationReport.Cause.MISSING_IN_MONGODB)
                + r.get().count(ReconciliationReport.Cause.EXTRA_IN_MONGODB)
                + r.get().count(ReconciliationReport.Cause.DIFFERENT)).register(registry);
    }

    private void counter(MeterRegistry registry, String outcome,
                         java.util.function.ToDoubleFunction<ForwardSyncStatus> value) {
        FunctionCounter.builder("sync.forward.changes", status, value).tag("outcome", outcome).register(registry);
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 15_000)
    void refresh() {
        try {
            forward.slot();
        } catch (Exception e) {
            log.debug("Slot not readable: {}", e.toString());
        }
        try {
            lastReport.set(reconciler.latest());
        } catch (RuntimeException e) {
            log.debug("Last reconciliation not readable: {}", e.toString());
        }
    }
}
