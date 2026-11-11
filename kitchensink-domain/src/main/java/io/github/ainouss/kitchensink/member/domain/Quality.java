package io.github.ainouss.kitchensink.member.domain;

/**
 * A rule that a migrated member breaks. Such members are kept as they are and flagged, never rewritten.
 */
public enum Quality {
    /** R2: the name has a digit. */
    NAME_HAS_DIGIT,
    /** R1: the name is empty or longer than 25 characters. */
    NAME_LENGTH,
    /** R3: the email is empty or not well formed. */
    EMAIL_SYNTAX,
    /** R5: the phone is not 10 to 12 digits. */
    PHONE_FORMAT
}
