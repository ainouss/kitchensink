package io.github.ainouss.kitchensink.member.adapter.in.api;

import java.net.URI;
import java.util.Comparator;
import java.util.List;

import jakarta.validation.Validator;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import io.github.ainouss.kitchensink.member.adapter.in.api.NewMemberRequest.FieldError;
import io.github.ainouss.kitchensink.member.adapter.in.api.NewMemberRequest.InvalidMemberRequestException;
import io.github.ainouss.kitchensink.member.application.FindMembers;
import io.github.ainouss.kitchensink.member.application.RegisterMember;
import io.github.ainouss.kitchensink.member.domain.Member;
import io.github.ainouss.kitchensink.member.domain.NewMember;

/**
 * The versioned API for new clients. The legacy {@code /rest/members} stays unchanged beside it.
 * Returns 201 with {@code Location}, errors as problem details in the client's language, and paged lists.
 */
@RestController
@RequestMapping("/api/v1/members")
@Tag(name = "members", description = "Register members and read them")
class MemberApiController {

    static final int DEFAULT_PAGE_SIZE = 25;

    private final FindMembers find;
    private final RegisterMember register;
    private final Validator validator;

    MemberApiController(FindMembers find, RegisterMember register, Validator validator) {
        this.find = find;
        this.register = register;
        this.validator = validator;
    }

    // No `consumes`, so a non-JSON body gets a 415 problem detail instead of an empty response.
    @PostMapping
    @Operation(summary = "Register a member", requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true, content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = NewMemberRequest.class))))
    @ApiResponse(responseCode = "201", description = "Registered; Location is the member's address")
    @ApiResponse(responseCode = "400", description = "The member breaks the rules, or the body is not a member",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Another member has this email",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<MemberResource> register(@RequestBody JsonNode body) {
        NewMember registration = NewMemberRequest.parse(body);
        // Spring's validator gives the messages in the client's language.
        List<FieldError> errors = validator.validate(registration).stream()
                .map(violation -> new FieldError(violation.getPropertyPath().toString(), violation.getMessage()))
                .sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::message))
                .toList();
        if (!errors.isEmpty()) {
            throw new InvalidMemberRequestException(errors);
        }
        Member member = register.register(registration);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(member.id())
                .toUri();
        return ResponseEntity.created(location).body(MemberResource.of(member));
    }

    @GetMapping("/{id}")
    @Operation(summary = "A member by id")
    @ApiResponse(responseCode = "200", description = "The member")
    @ApiResponse(responseCode = "404", description = "No member has this id",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    MemberResource byId(@PathVariable long id) {
        return find.byId(id).map(MemberResource::of).orElseThrow(() -> new MemberNotFoundException(id));
    }

    @GetMapping
    @Operation(summary = "A page of members, by name then id")
    @ApiResponse(responseCode = "200", description = "The page; past the last one, an empty page with the totals")
    @ApiResponse(responseCode = "400", description = "page below 0, or size outside 1 to 100",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    MemberResource.Page page(
            @Parameter(description = "0-based") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "1 to " + FindMembers.MAX_PAGE_SIZE)
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        return MemberResource.Page.of(find.page(page, size));
    }

    /** No member has this id. */
    static class MemberNotFoundException extends RuntimeException {

        final long id;

        MemberNotFoundException(long id) {
            super("No member has the id " + id);
            this.id = id;
        }
    }
}
