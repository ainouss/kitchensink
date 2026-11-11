package io.github.ainouss.kitchensink.member.domain;

import java.util.EnumSet;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

/**
 * Checks a stored member against the registration rules (R1 to R5) and lists what it breaks.
 * It reuses {@link NewMember}'s constraints, so there is only one copy of each rule.
 */
public final class MemberRules {

    private MemberRules() {
    }

    /** The rules the member breaks; empty when it would pass as a registration. */
    public static Set<Quality> quality(Validator validator, String name, String email, String phoneNumber) {
        Set<ConstraintViolation<NewMember>> violations = validator.validate(new NewMember(name, email, phoneNumber));
        Set<Quality> quality = EnumSet.noneOf(Quality.class);
        for (ConstraintViolation<NewMember> violation : violations) {
            String property = violation.getPropertyPath().toString();
            String constraint = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
            switch (property) {
                case "name" -> quality.add("Pattern".equals(constraint) ? Quality.NAME_HAS_DIGIT : Quality.NAME_LENGTH);
                case "email" -> quality.add(Quality.EMAIL_SYNTAX);
                case "phoneNumber" -> quality.add(Quality.PHONE_FORMAT);
                default -> throw new IllegalStateException("a rule on an unknown property: " + property);
            }
        }
        return quality;
    }
}
