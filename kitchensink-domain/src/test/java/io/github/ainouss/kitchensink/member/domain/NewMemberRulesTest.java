package io.github.ainouss.kitchensink.member.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.validator.HibernateValidator;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The registration rules R1 to R5 at their limits, and the quality flags of migrated members. */
class NewMemberRulesTest {

    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    static Map<String, String> messages(NewMember member) {
        return VALIDATOR.validate(member).stream().collect(Collectors.toMap(
                v -> v.getPropertyPath().toString(), v -> v.getMessage(), (a, b) -> a + " | " + b));
    }

    @Test
    void a_valid_registration_has_no_violation() {
        assertThat(messages(new NewMember("Jane Doe", "jane@mailinator.com", "2125551234"))).isEmpty();
    }

    @ParameterizedTest(name = "name ''{0}'' valid: {1}")
    @CsvSource(delimiter = '|', value = {
            "J                         | true",   // R1: 1 character
            "Abcdefghijklmnopqrstuvwxy | true",   // R1: 25 characters
            "Abcdefghijklmnopqrstuvwxyz| false",  // R1: 26 characters
            "Jane 2                    | false",  // R2: a digit
            "O'Brien-Smith, Jr.        | true",   // R2: punctuation passes, whatever the message says
            "Zoë Ørsted                | true"})
    void the_name_rules(String name, boolean valid) {
        assertThat(messages(new NewMember(name.strip(), "jane@mailinator.com", "2125551234")).containsKey("name"))
                .isEqualTo(!valid);
    }

    @Test
    void an_empty_name_breaks_r1() {
        assertThat(messages(new NewMember("", "jane@mailinator.com", "2125551234"))).containsKey("name");
    }

    @ParameterizedTest(name = "email ''{0}'' valid: {1}")
    @CsvSource(delimiter = '|', value = {
            "a@b                 | true",    // R3: Hibernate Validator's @Email accepts a dotless domain
            "jane@mailinator.com | true",
            "jane@@mailinator.com| false",
            "jane                | false"})
    void the_email_rules(String email, boolean valid) {
        assertThat(messages(new NewMember("Jane", email.strip(), "2125551234")).containsKey("email")).isEqualTo(!valid);
    }

    @Test
    void an_empty_or_missing_email_breaks_r3() {
        assertThat(messages(new NewMember("Jane", "", "2125551234"))).containsKey("email");
        assertThat(messages(new NewMember("Jane", null, "2125551234"))).containsKey("email");
    }

    @ParameterizedTest(name = "phone ''{0}'' valid: {1}")
    @CsvSource(delimiter = '|', value = {
            "212555123     | false",   // 9
            "2125551234    | true",    // 10
            "212555123456  | true",    // 12
            "2125551234567 | false",   // 13
            "+33612345678  | true",    // R5: a leading + passes, as in the legacy (@Digits reads a number)
            "+336123456789 | false",   // 13 characters
            "212-555-1234  | false"})  // not a number
    void the_phone_rules(String phone, boolean valid) {
        assertThat(messages(new NewMember("Jane", "jane@mailinator.com", phone.strip())).containsKey("phoneNumber"))
                .isEqualTo(!valid);
    }

    /** A validator's language is fixed when it is built, so we build one per language (R16). */
    static String nameMessage(Locale locale, NewMember member) {
        Validator validator = Validation.byProvider(HibernateValidator.class).configure().defaultLocale(locale)
                .buildValidatorFactory().getValidator();
        return validator.validateProperty(member, "name").iterator().next().getMessage();
    }

    @Test
    void the_messages_are_the_legacys_in_its_three_languages() {
        NewMember member = new NewMember("Jane 2", "jane@mailinator.com", "2125551234");
        assertThat(nameMessage(Locale.ENGLISH, member)).isEqualTo("must contain only letters and spaces");
        assertThat(nameMessage(Locale.FRENCH, member)).isEqualTo("ne doit contenir que des lettres et des espaces");
        assertThat(nameMessage(Locale.of("es"), member)).isEqualTo("debe contener sólo letras y espacios");
    }

    @Test
    void a_carried_member_is_flagged_with_what_it_breaks() {
        assertThat(MemberRules.quality(VALIDATOR, "Ada Lovelace 7", "a@@b.com", "212-555-1234"))
                .containsExactlyInAnyOrder(Quality.NAME_HAS_DIGIT, Quality.EMAIL_SYNTAX, Quality.PHONE_FORMAT);
        assertThat(MemberRules.quality(VALIDATOR, "x".repeat(30), "a@b.com", "2125551234"))
                .containsExactly(Quality.NAME_LENGTH);
        assertThat(MemberRules.quality(VALIDATOR, "Ada Lovelace", "ada@b.com", "2125551234")).isEmpty();
    }

    @Property
    void any_name_of_1_to_25_letters_is_valid(@ForAll @AlphaChars @StringLength(min = 1, max = 25) String name) {
        assertThat(messages(new NewMember(name, "jane@mailinator.com", "2125551234"))).doesNotContainKey("name");
    }

    @Property
    void any_phone_of_10_to_12_digits_is_valid(@ForAll @NumericChars @StringLength(min = 10, max = 12) String phone) {
        assertThat(messages(new NewMember("Jane", "jane@mailinator.com", phone))).doesNotContainKey("phoneNumber");
    }

    @Property
    void a_name_with_a_digit_is_always_flagged(@ForAll @AlphaChars @StringLength(min = 0, max = 20) String prefix,
                                                @ForAll @NumericChars @StringLength(1) String digit) {
        Set<Quality> quality = MemberRules.quality(VALIDATOR, prefix + digit, "jane@mailinator.com", "2125551234");
        assertThat(quality).contains(Quality.NAME_HAS_DIGIT);
    }
}
