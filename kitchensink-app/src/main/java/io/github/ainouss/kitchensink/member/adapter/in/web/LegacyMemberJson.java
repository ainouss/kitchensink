package io.github.ainouss.kitchensink.member.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.github.ainouss.kitchensink.member.domain.Member;

/**
 * A member as the legacy {@code /rest/members} wrote it, with its fields in alphabetical order (R18).
 */
@JsonPropertyOrder(alphabetic = true)
record LegacyMemberJson(String email, long id, String name, String phoneNumber) {

    static LegacyMemberJson of(Member member) {
        return new LegacyMemberJson(member.email(), member.id(), member.name(), member.phoneNumber());
    }
}
