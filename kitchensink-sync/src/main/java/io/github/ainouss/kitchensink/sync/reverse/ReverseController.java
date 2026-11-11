package io.github.ainouss.kitchensink.sync.reverse;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator API for the reverse stream and the id sequences, used by ks cutover and ks rollback. Not exposed through
 * the facade. {@code from=now} starts from the current position; {@code resume} continues from the saved one.
 */
@RestController
class ReverseController {

    private final ReverseSync reverse;
    private final ReverseSyncStatus status;
    private final Sequences sequences;

    ReverseController(ReverseSync reverse, ReverseSyncStatus status, Sequences sequences) {
        this.reverse = reverse;
        this.status = status;
        this.sequences = sequences;
    }

    @PostMapping("/sync/reverse/start")
    Map<String, Object> start(@RequestParam(defaultValue = "resume") String from) {
        reverse.start("now".equals(from));
        return Map.of("state", status.state());
    }

    @PostMapping("/sync/reverse/stop")
    Map<String, Object> stop() {
        reverse.stop();
        return Map.of("state", status.state());
    }

    @PostMapping("/sync/reverse/drain")
    ResponseEntity<?> drain(@RequestParam(defaultValue = "30") int timeoutSeconds) throws InterruptedException {
        try {
            Duration took = reverse.drain(Duration.ofSeconds(timeoutSeconds));
            return ResponseEntity.ok(Map.of("drained", true, "tookMillis", took.toMillis()));
        } catch (TimeoutException e) {
            return ResponseEntity.of(ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT,
                    "The drain marker did not come through within " + timeoutSeconds + " s")).build();
        }
    }

    @PostMapping("/sync/target-sequence")
    Map<String, Object> seedTarget() throws SQLException {
        return Map.of("mongodbNextIdAbove", sequences.seedTarget());
    }

    @PostMapping("/sync/legacy-sequence")
    Map<String, Object> moveLegacy() throws SQLException {
        return Map.of("memberSeq", sequences.moveLegacySequence());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> conflict(IllegalStateException e) {
        return ResponseEntity.of(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage())).build();
    }
}
