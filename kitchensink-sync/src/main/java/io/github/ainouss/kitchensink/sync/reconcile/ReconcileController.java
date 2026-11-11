package io.github.ainouss.kitchensink.sync.reconcile;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator API for reconciliation: {@code POST} runs one and returns its report; {@code GET /latest} returns the
 * last one. Used by the cutover. Not exposed through the facade.
 */
@RestController
@RequestMapping("/sync/reconcile")
class ReconcileController {

    private final Reconciler reconciler;

    ReconcileController(Reconciler reconciler) {
        this.reconciler = reconciler;
    }

    @PostMapping
    ReconciliationReport run() {
        return reconciler.reconcile();
    }

    @GetMapping("/latest")
    ResponseEntity<ReconciliationReport> latest() {
        ReconciliationReport latest = reconciler.latest();
        return latest == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(latest);
    }

    @ExceptionHandler(Reconciler.AlreadyRunningException.class)
    ResponseEntity<ProblemDetail> running(Reconciler.AlreadyRunningException e) {
        return ResponseEntity.of(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage())).build();
    }
}
