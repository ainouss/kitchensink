package io.github.ainouss.kitchensink.member.adapter.in.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

import io.github.ainouss.kitchensink.member.domain.NewMember;

/**
 * The body of a registration in the versioned API: {@code name}, {@code email} and {@code phoneNumber}, as strings.
 * Read by hand so that an {@code id} (R7), an unknown field or a non-string value is refused, not silently ignored.
 */
@Schema(name = "NewMember", requiredProperties = {"name", "email", "phoneNumber"})
public record NewMemberRequest(
        @Schema(example = "Ada Lovelace", maxLength = 25, pattern = "[^0-9]*") String name,
        @Schema(example = "ada@mailinator.com", format = "email") String email,
        @Schema(example = "2125551234", minLength = 10, maxLength = 12) String phoneNumber) {

    private static final Set<String> FIELDS = Set.of("name", "email", "phoneNumber");

    /**
     * @throws InvalidMemberRequestException listing each unknown field and each value that is not a string
     */
    static NewMember parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new InvalidMemberRequestException(List.of(new FieldError("", "must be a JSON object")));
        }
        List<FieldError> errors = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : body.properties()) {
            if (!FIELDS.contains(field.getKey())) {
                errors.add(new FieldError(field.getKey(), "id".equals(field.getKey())
                        ? "is assigned by the server" : "is not a field of a member"));
            }
        }
        String name = text(body, "name", errors);
        String email = text(body, "email", errors);
        String phoneNumber = text(body, "phoneNumber", errors);
        if (!errors.isEmpty()) {
            throw new InvalidMemberRequestException(errors);
        }
        return new NewMember(name, email, phoneNumber);
    }

    private static String text(JsonNode body, String field, List<FieldError> errors) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null; // reported later by the domain's @NotNull
        }
        if (!value.isString()) {
            errors.add(new FieldError(field, "must be a string"));
            return null;
        }
        return value.stringValue();
    }

    /** One field and what is wrong with it. */
    @Schema(name = "FieldError")
    public record FieldError(@Schema(example = "phoneNumber") String field,
                             @Schema(example = "size must be between 10 and 12") String message) {
    }

    /** The request is not a valid registration; lists every field at fault. */
    static class InvalidMemberRequestException extends RuntimeException {

        private final List<FieldError> errors;

        InvalidMemberRequestException(List<FieldError> errors) {
            super("invalid member");
            this.errors = List.copyOf(errors);
        }

        List<FieldError> errors() {
            return errors;
        }
    }
}
