package io.github.ainouss.kitchensink.member.application;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.ainouss.kitchensink.member.domain.EmailTakenException;
import io.github.ainouss.kitchensink.member.domain.Member;
import io.github.ainouss.kitchensink.member.domain.NewMember;

/**
 * Registers a member: checks the rules (R1 to R5), checks the email is free (R11), then inserts it.
 * If two registrations race for one email, the unique index stops the second one (R12).
 */
@Service
public class RegisterMember {

    private static final Logger log = LoggerFactory.getLogger(RegisterMember.class);

    private final Validator validator;
    private final MemberRepository members;
    private final MemberIds ids;

    public RegisterMember(Validator validator, MemberRepository members, MemberIds ids) {
        this.validator = validator;
        this.members = members;
        this.ids = ids;
    }

    /**
     * @throws ConstraintViolationException when the registration breaks R1 to R5
     * @throws EmailTakenException          when another member has its email
     */
    public Member register(NewMember registration) {
        Set<ConstraintViolation<NewMember>> violations = validator.validate(registration);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        if (members.existsByEmail(registration.email())) {
            throw new EmailTakenException(registration.email());
        }
        Member member = Member.registered(ids.next(), registration);
        members.insert(member);
        // R15: log the id, not the name, to keep personal data out of logs.
        log.info("Registered member {}", member.id());
        return member;
    }
}
