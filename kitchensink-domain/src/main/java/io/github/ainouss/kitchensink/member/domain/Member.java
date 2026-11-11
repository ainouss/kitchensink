package io.github.ainouss.kitchensink.member.domain;

import java.util.Objects;

/**
 * A registered member, stored as one document. Values are never trimmed or lower-cased (R4, R14).
 * A member migrated from the legacy may break today's rules: the rules only apply to a {@link NewMember}.
 *
 * @param id numeric id given by the server (R6), or the legacy's id for a migrated member
 */
public record Member(long id, String name, String email, String phoneNumber) {

    public Member {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(phoneNumber, "phoneNumber");
    }

    /** Builds the member from a registration and its new id. */
    public static Member registered(long id, NewMember registration) {
        return new Member(id, registration.name(), registration.email(), registration.phoneNumber());
    }
}
