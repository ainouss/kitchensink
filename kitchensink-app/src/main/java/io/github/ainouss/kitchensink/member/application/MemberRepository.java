package io.github.ainouss.kitchensink.member.application;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import io.github.ainouss.kitchensink.member.domain.EmailTakenException;
import io.github.ainouss.kitchensink.member.domain.Member;

/** Where members are stored, one document per member. */
public interface MemberRepository {

    /**
     * Adds a member, atomically.
     *
     * @throws EmailTakenException when another member has this email (checked by a unique index, R12)
     */
    void insert(Member member);

    Optional<Member> findById(long id);

    boolean existsByEmail(String email);

    /** Every member, by name then id (R8), one at a time so memory stays flat (R17). */
    void forEachByName(Consumer<Member> action);

    /** One page of members, by name then id. */
    List<Member> page(int page, int size);

    long count();
}
