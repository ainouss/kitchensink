package io.github.ainouss.kitchensink.facade.control;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.ainouss.kitchensink.facade.FacadeProperties;

/**
 * {@code GET /facade/control} shows the routing; {@code PUT} changes it and needs the admin token as a Bearer token.
 * Without a configured token, the routing is read only.
 */
@RestController
@RequestMapping("/facade/control")
class ControlController {

    private final ControlStore store;
    private final byte[] token;

    ControlController(ControlStore store, FacadeProperties properties) {
        this.store = store;
        String configured = properties.adminToken();
        this.token = configured == null || configured.isBlank() ? null : configured.getBytes(StandardCharsets.UTF_8);
    }

    /** The new reads and writes modes, and who asks for the change. */
    record ControlChange(Control.Reads reads, Control.Writes writes, String by) {
    }

    @GetMapping
    Control current() {
        return store.current();
    }

    @PutMapping
    ResponseEntity<?> change(@RequestHeader(name = "Authorization", required = false) String authorization,
                             @RequestBody ControlChange change) {
        if (token == null) {
            return problem(HttpStatus.FORBIDDEN, "No FACADE_ADMIN_TOKEN is configured: the control is read only");
        }
        if (authorization == null || !authorization.startsWith("Bearer ") || !MessageDigest.isEqual(token,
                authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8))) {
            return problem(HttpStatus.UNAUTHORIZED, "A valid admin token is required");
        }
        if (change.reads() == null || change.writes() == null) {
            return problem(HttpStatus.BAD_REQUEST, "Both reads and writes are required");
        }
        String by = change.by() == null || change.by().isBlank() ? "facade-api" : change.by();
        return ResponseEntity.ok(store.set(change.reads(), change.writes(), by));
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.of(ProblemDetail.forStatusAndDetail(status, detail)).build();
    }
}
