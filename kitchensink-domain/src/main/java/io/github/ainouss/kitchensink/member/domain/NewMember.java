package io.github.ainouss.kitchensink.member.domain;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A member to register, before it has an id. Same constraints and messages as the legacy entity (rules R1 to R5).
 * Note: the name message says "letters and spaces", but punctuation passes, as in the legacy (R2).
 */
public record NewMember(

        @NotNull
        @Size(min = 1, max = 25)
        @Pattern(regexp = "[^0-9]*", message = "{name_validation_message}")
        String name,

        @NotNull
        @NotEmpty
        @Email
        String email,

        @NotNull
        @Size(min = 10, max = 12)
        @Digits(fraction = 0, integer = 12)
        String phoneNumber) {
}
