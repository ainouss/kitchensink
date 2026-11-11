package io.github.ainouss.kitchensink.sync.status;

import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.ainouss.kitchensink.sync.SyncDirection;
import io.github.ainouss.kitchensink.sync.SyncProperties;
import io.github.ainouss.kitchensink.sync.forward.ForwardSync;
import io.github.ainouss.kitchensink.sync.forward.ForwardSyncStatus;
import io.github.ainouss.kitchensink.sync.forward.LegacyWal;
import io.github.ainouss.kitchensink.sync.reconcile.Reconciler;
import io.github.ainouss.kitchensink.sync.reconcile.ReconciliationReport;
import io.github.ainouss.kitchensink.sync.reverse.ReverseSync;
import io.github.ainouss.kitchensink.sync.reverse.ReverseSyncStatus;

/**
 * Operator API for the worker's status and the forward stream's controls, used by ks cutover and ks rollback. Not
 * exposed through the facade. Only one direction runs at a time, so starting the forward stream while the reverse
 * stream runs returns 409.
 */
@RestController
class SyncStatusController {

    private final ForwardSync forward;
    private final ForwardSyncStatus status;
    private final ReverseSync reverse;
    private final ReverseSyncStatus reverseStatus;
    private final Reconciler reconciler;
    private final SyncProperties properties;
    private final SyncDirection direction;

    SyncStatusController(ForwardSync forward, ForwardSyncStatus status, ReverseSync reverse,
                         ReverseSyncStatus reverseStatus, Reconciler reconciler, SyncProperties properties,
                         SyncDirection direction) {
        this.forward = forward;
        this.status = status;
        this.reverse = reverse;
        this.reverseStatus = reverseStatus;
        this.reconciler = reconciler;
        this.properties = properties;
        this.direction = direction;
    }

    @GetMapping("/sync/status")
    Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> directionBody = new LinkedHashMap<>();
        var recorded = direction.recorded();
        directionBody.put("recorded", recorded.map(SyncDirection.Recorded::direction).orElse(null));
        directionBody.put("recordedAt", recorded.map(SyncDirection.Recorded::at).orElse(null));
        directionBody.put("decidedBy", recorded.isPresent() ? "the last stream started" : "the environment");
        body.put("direction", directionBody);
        body.put("forward", forward());
        Map<String, Object> reverseBody = new LinkedHashMap<>();
        reverseBody.put("state", reverseStatus.state());
        reverseBody.put("enabledAtStartup", reverse.isAutoStartup());
        reverseBody.put("failure", reverseStatus.failure());
        reverseBody.put("lagSeconds", reverseStatus.lagSeconds(System.currentTimeMillis()));
        reverseBody.put("lastBatchAt", reverseStatus.lastBatchAt());
        reverseBody.put("counters", Map.of("upserted", reverseStatus.upserted(), "deleted", reverseStatus.deleted(),
                "echoesSkipped", reverseStatus.echoesSkipped(), "quarantined", reverseStatus.quarantined(),
                "batches", reverseStatus.batches()));
        body.put("reverse", reverseBody);
        ReconciliationReport latest = reconciler.latest();
        body.put("lastReconciliation", latest == null ? null : Map.of(
                "id", latest.id(), "startedAt", latest.startedAt(), "green", latest.green(),
                "identical", latest.identical(), "counts", latest.counts(), "inFlight", latest.inFlight()));
        return body;
    }

    @PostMapping("/sync/forward/stop")
    Map<String, Object> stop() {
        forward.stop();
        return forward();
    }

    @PostMapping("/sync/forward/start")
    Map<String, Object> start() {
        if (reverse.isRunning()) {
            throw new IllegalStateException("The reverse stream runs: drain and stop it before the forward stream "
                    + "starts (one direction at a time)");
        }
        forward.start();
        return forward();
    }

    @PostMapping("/sync/forward/drain")
    ResponseEntity<?> drain(@RequestParam(defaultValue = "30") int timeoutSeconds)
            throws SQLException, InterruptedException {
        try {
            Duration took = forward.drain(Duration.ofSeconds(timeoutSeconds));
            return ResponseEntity.ok(Map.of("drained", true, "tookMillis", took.toMillis()));
        } catch (TimeoutException e) {
            return ResponseEntity.of(ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT,
                    "The drain marker did not come through within " + timeoutSeconds + " s")).build();
        }
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> conflict(IllegalStateException e) {
        return ResponseEntity.of(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage())).build();
    }

    private Map<String, Object> forward() {
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("state", status.state());
        forward.put("enabledAtStartup", this.forward.isAutoStartup());
        forward.put("slot", properties.forward().slot());
        forward.put("failure", status.failure());
        forward.put("lagSeconds", status.lagSeconds(System.currentTimeMillis()));
        forward.put("appliedLsn", status.lastVersion() == null ? null : LegacyWal.lsn(status.lastVersion().lsn()));
        forward.put("lastSourceCommitAt", status.lastSourceCommit());
        forward.put("lastBatchAt", status.lastBatchAt());
        try {
            LegacyWal.SlotState slot = this.forward.slot();
            forward.put("legacyCurrentLsn", slot.currentLsn());
            forward.put("slotExists", slot.exists());
            forward.put("slotActive", slot.active());
            forward.put("confirmedFlushLsn", slot.confirmedFlushLsn());
            forward.put("retainedWalBytes", slot.retainedBytes());
        } catch (SQLException e) {
            forward.put("legacyError", e.getMessage());
        }
        forward.put("counters", Map.of(
                "snapshotRows", status.snapshotRows(),
                "upserted", status.upserted(),
                "deleted", status.deleted(),
                "skipped", status.skipped(),
                "quarantined", status.quarantined(),
                "batches", status.batches()));
        return forward;
    }
}
