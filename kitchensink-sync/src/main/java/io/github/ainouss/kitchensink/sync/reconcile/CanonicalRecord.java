package io.github.ainouss.kitchensink.sync.reconcile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import io.github.ainouss.kitchensink.sync.member.MemberDocumentFields;
import io.github.ainouss.kitchensink.sync.member.MemberRow;

/**
 * A member in one canonical text form, {@code id|name|email|phoneNumber}, so both databases can be compared and
 * hashed (SHA-256) the same way. Text is Unicode NFC, {@code \} and {@code |} are escaped, and null is {@code \0}.
 */
final class CanonicalRecord {

    private CanonicalRecord() {
    }

    static String form(MemberRow row) {
        return row.id() + "|" + text(row.name()) + "|" + text(row.email()) + "|" + text(row.phoneNumber());
    }

    static byte[] sha256(MemberRow row) {
        return digest().digest(form(row).getBytes(StandardCharsets.UTF_8));
    }

    static boolean same(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }

    /** The fields that differ, with both values, named as in the document. */
    static List<FieldDifference> differences(MemberRow legacy, MemberRow mongo) {
        List<FieldDifference> differences = new ArrayList<>(3);
        compare(MemberDocumentFields.NAME, legacy.name(), mongo.name(), differences);
        compare(MemberDocumentFields.EMAIL, legacy.email(), mongo.email(), differences);
        compare(MemberDocumentFields.PHONE_NUMBER, legacy.phoneNumber(), mongo.phoneNumber(), differences);
        return differences;
    }

    record FieldDifference(String field, String legacy, String mongo) {
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", e);
        }
    }

    private static void compare(String field, String legacy, String mongo, List<FieldDifference> differences) {
        if (!Objects.equals(nfc(legacy), nfc(mongo))) {
            differences.add(new FieldDifference(field, legacy, mongo));
        }
    }

    private static String text(String value) {
        if (value == null) {
            return "\\0";
        }
        return nfc(value).replace("\\", "\\\\").replace("|", "\\|");
    }

    private static String nfc(String value) {
        if (value == null || Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            return value;
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }
}
