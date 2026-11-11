package io.github.ainouss.kitchensink.member.application;

/**
 * Gives ids to new members (R6): numeric, increasing, and never one already used, migrated members included.
 */
public interface MemberIds {

    long next();
}
