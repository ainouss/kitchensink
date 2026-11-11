package io.github.ainouss.kitchensink.member.domain;

/**
 * Another member already has this email (R11, R12). Emails compare case-sensitively, as in the legacy (R4).
 */
public class EmailTakenException extends RuntimeException {

    private final String email;

    public EmailTakenException(String email) {
        super("Email taken");
        this.email = email;
    }

    public String email() {
        return email;
    }
}
