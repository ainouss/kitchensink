package io.github.ainouss.kitchensink.member.adapter.in.api;

import java.net.URI;

import jakarta.validation.ConstraintViolationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import io.github.ainouss.kitchensink.member.adapter.in.api.MemberApiController.MemberNotFoundException;
import io.github.ainouss.kitchensink.member.adapter.in.api.NewMemberRequest.InvalidMemberRequestException;
import io.github.ainouss.kitchensink.member.domain.EmailTakenException;

/**
 * Turns the versioned API's errors into RFC 9457 problem details, with one stable {@code type} per kind of error.
 * Applies to {@link MemberApiController} only: the legacy API keeps its own error bodies.
 */
@RestControllerAdvice(assignableTypes = MemberApiController.class)
class MemberApiProblems {

    static final URI INVALID_MEMBER = URI.create("urn:kitchensink:problem:invalid-member");
    static final URI EMAIL_TAKEN = URI.create("urn:kitchensink:problem:email-taken");
    static final URI MEMBER_NOT_FOUND = URI.create("urn:kitchensink:problem:member-not-found");
    static final URI BAD_REQUEST = URI.create("urn:kitchensink:problem:bad-request");
    static final URI UNSUPPORTED_MEDIA_TYPE = URI.create("urn:kitchensink:problem:unsupported-media-type");

    @ExceptionHandler(InvalidMemberRequestException.class)
    ResponseEntity<ProblemDetail> invalid(InvalidMemberRequestException e) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, INVALID_MEMBER, "Invalid member",
                "The member breaks the registration rules; see errors.");
        problem.setProperty("errors", e.errors());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> constraints(ConstraintViolationException e) {
        // Rare: the controller already validated, but RegisterMember checks again.
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, INVALID_MEMBER, "Invalid member", e.getMessage());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(EmailTakenException.class)
    ResponseEntity<ProblemDetail> emailTaken(EmailTakenException e) {
        return ResponseEntity.of(problem(HttpStatus.CONFLICT, EMAIL_TAKEN, "Email taken",
                "Another member has this email.")).build();
    }

    @ExceptionHandler(MemberNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(MemberNotFoundException e) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND, "Member not found", e.getMessage());
        problem.setProperty("id", e.id);
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.of(problem(HttpStatus.BAD_REQUEST, BAD_REQUEST, "Bad request",
                "The request body is not JSON.")).build();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> mismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.of(problem(HttpStatus.BAD_REQUEST, BAD_REQUEST, "Bad request",
                "'" + e.getName() + "' must be a whole number.")).build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> outOfRange(IllegalArgumentException e) {
        // Paging out of range (see FindMembers.page).
        return ResponseEntity.of(problem(HttpStatus.BAD_REQUEST, BAD_REQUEST, "Bad request", e.getMessage()))
                .build();
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> notJson(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.of(problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MEDIA_TYPE,
                "Unsupported media type", "The request body must be application/json.")).build();
    }

    private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        return problem;
    }
}
