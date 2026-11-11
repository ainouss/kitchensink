package io.github.ainouss.kitchensink.member.adapter.in.web;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.hibernate.validator.HibernateValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

import io.github.ainouss.kitchensink.member.application.FindMembers;
import io.github.ainouss.kitchensink.member.application.RegisterMember;
import io.github.ainouss.kitchensink.member.domain.EmailTakenException;
import io.github.ainouss.kitchensink.member.domain.NewMember;

/**
 * The legacy REST API, {@code /rest/members}, answering exactly as the legacy did (rules R6 to R13, R17, R18).
 * Also served under {@code /kitchensink/rest}, so the facade can forward requests unchanged.
 *
 * <p>Error messages use the server's language, not the request's, as in the legacy.
 */
@RestController
@RequestMapping(path = {"/rest/members", "/kitchensink/rest/members"})
class LegacyMemberRestController {

    private static final Logger log = LoggerFactory.getLogger(LegacyMemberRestController.class);
    private static final byte[] OPEN = "[".getBytes(StandardCharsets.UTF_8);
    private static final byte[] COMMA = ",".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CLOSE = "]".getBytes(StandardCharsets.UTF_8);

    private final FindMembers find;
    private final RegisterMember register;
    private final JsonMapper json;
    private final Validator serverLocaleValidator;

    LegacyMemberRestController(FindMembers find, RegisterMember register, JsonMapper json) {
        this.find = find;
        this.register = register;
        this.json = json;
        this.serverLocaleValidator = Validation.byProvider(HibernateValidator.class).configure()
                .defaultLocale(Locale.getDefault()).buildValidatorFactory().getValidator();
    }

    /**
     * Every member, by name then id (R8), streamed one at a time. The legacy loads them all in memory at once (R17).
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    StreamingResponseBody listAllMembers() {
        return (OutputStream out) -> {
            out.write(OPEN);
            boolean[] first = {true};
            find.eachByName(member -> {
                try {
                    if (!first[0]) {
                        out.write(COMMA);
                    }
                    first[0] = false;
                    out.write(json.writeValueAsBytes(LegacyMemberJson.of(member)));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            out.write(CLOSE);
        };
    }

    /**
     * One member, or 404 with no body (R13). An id that is not a number matched no route on the legacy: also 404 with
     * no body, not this framework's error page.
     */
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LegacyMemberJson> lookupMemberById(@PathVariable String id) {
        if (id.isEmpty() || !id.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return ResponseEntity.notFound().build();
        }
        long number;
        try {
            number = Long.parseLong(id);
        } catch (NumberFormatException tooLarge) {
            return ResponseEntity.notFound().build();
        }
        return find.byId(number).map(LegacyMemberJson::of).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Registers a member: 200 (R9), 400 per field (R10), 409 when the email is taken (R11, R12),
     * or 400 {@code {"error": ...}} for anything else (R7).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, String>> createMember(@RequestBody LegacyMemberRequest request) {
        try {
            if (request.id() != null) {
                // R7: the legacy also refused a member sent with an id.
                return error(HttpStatus.BAD_REQUEST, "error", "A member's id is assigned by the server");
            }
            NewMember registration = new NewMember(request.name(), request.email(), request.phoneNumber());
            Set<ConstraintViolation<NewMember>> violations = serverLocaleValidator.validate(registration);
            if (!violations.isEmpty()) {
                Map<String, String> fields = new LinkedHashMap<>();
                violations.forEach(v -> fields.put(v.getPropertyPath().toString(), v.getMessage()));
                return ResponseEntity.badRequest().body(fields);
            }
            register.register(registration);
            return ResponseEntity.ok().build();
        } catch (EmailTakenException e) {
            return error(HttpStatus.CONFLICT, "email", "Email taken");
        } catch (RuntimeException e) {
            log.warn("Registration failed", e);
            return error(HttpStatus.BAD_REQUEST, "error", String.valueOf(e.getMessage()));
        }
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String key, String message) {
        return ResponseEntity.status(status).body(Map.of(key, message));
    }

    /** The legacy's request body. It may hold an id, which is refused (R7). */
    record LegacyMemberRequest(Long id, String name, String email, String phoneNumber) {
    }
}
