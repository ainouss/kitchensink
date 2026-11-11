package io.github.ainouss.kitchensink.member.adapter.in.api;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import io.github.ainouss.kitchensink.member.application.FindMembers;
import io.github.ainouss.kitchensink.member.domain.Member;

/** A member, as the versioned API shows it. */
@Schema(name = "Member")
public record MemberResource(
        @Schema(example = "42") long id,
        @Schema(example = "Ada Lovelace") String name,
        @Schema(example = "ada@mailinator.com") String email,
        @Schema(example = "2125551234") String phoneNumber) {

    static MemberResource of(Member member) {
        return new MemberResource(member.id(), member.name(), member.email(), member.phoneNumber());
    }

    /** A page of members, by name then id. */
    @Schema(name = "MemberPage")
    public record Page(
            List<MemberResource> content,
            @Schema(description = "0-based", example = "0") int page,
            @Schema(example = "25") int size,
            @Schema(example = "10001") long totalElements,
            @Schema(example = "401") long totalPages) {

        static Page of(FindMembers.MemberPage members) {
            long pages = (members.totalElements() + members.size() - 1) / members.size();
            return new Page(members.content().stream().map(MemberResource::of).toList(), members.page(),
                    members.size(), members.totalElements(), pages);
        }
    }
}
