package io.github.ainouss.kitchensink.sync.member;

/** A row of the legacy {@code member} table, with its values unchanged. */
public record MemberRow(Long id, String name, String email, String phoneNumber) {

    public static final String TABLE = "member";
    public static final String ID = "id";
    public static final String NAME = "name";
    public static final String EMAIL = "email";
    public static final String PHONE_NUMBER = "phone_number";
}
