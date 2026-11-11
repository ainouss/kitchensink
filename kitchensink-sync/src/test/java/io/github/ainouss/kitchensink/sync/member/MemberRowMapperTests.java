package io.github.ainouss.kitchensink.sync.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Date;

import jakarta.validation.Validation;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import io.github.ainouss.kitchensink.member.adapter.out.mongo.MemberDocuments;

/** Checks the row-to-document mapper, and that its field names match the application's. */
class MemberRowMapperTests {

    private final MemberRowMapper mapper =
            new MemberRowMapper(Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void a_row_is_copied_unchanged_with_where_it_came_from() throws Exception {
        Document document = mapper.toDocument(new MemberRow(7L, "Ana García", "ana@mailinator.com", "2125550007"),
                new SyncVersion(900, 880), Instant.parse("2026-11-09T10:00:00Z"));

        assertThat(document.getLong("_id")).isEqualTo(7L);
        assertThat(document.getString("name")).isEqualTo("Ana García");
        assertThat(document.getString("phoneNumber")).isEqualTo("2125550007");
        assertThat(document.getInteger("schemaVersion")).isEqualTo(1);
        assertThat(document.containsKey("quality")).isFalse();
        assertThat(document.get("sync", Document.class)).isEqualTo(new Document("origin", "LEGACY")
                .append("txLsn", 900L).append("lsn", 880L).append("at", Date.from(Instant.parse("2026-11-09T10:00:00Z"))));
    }

    @Test
    void a_row_todays_rules_refuse_is_kept_as_it_is_and_flagged() throws Exception {
        Document document = mapper.toDocument(
                new MemberRow(997L, "Ada Lovelace 7 and a name far too long", "ada@@mailinator.com", "212-555-0997"),
                new SyncVersion(1, 1), Instant.now());

        assertThat(document.getString("name")).isEqualTo("Ada Lovelace 7 and a name far too long");
        assertThat(document.getString("email")).isEqualTo("ada@@mailinator.com");
        assertThat(MemberRowMapper.quality(document))
                .containsExactly("EMAIL_SYNTAX", "NAME_HAS_DIGIT", "NAME_LENGTH", "PHONE_FORMAT");
    }

    @Test
    void a_row_the_collection_cannot_hold_is_refused_with_the_reason() {
        assertThatThrownBy(() -> mapper.toDocument(new MemberRow(8L, "Ana", "ana@mailinator.com", null),
                SyncVersion.INITIAL_SNAPSHOT, Instant.now()))
                .isInstanceOf(UnmappableRowException.class).hasMessage("phone_number is null; the members collection requires it");
        assertThatThrownBy(() -> mapper.toBusinessDocument(new MemberRow(null, "Ana", "a@b", "2125550000")))
                .hasMessage("id is null");
    }

    @Test
    void the_inverse_gives_the_row_back() throws Exception {
        MemberRow row = new MemberRow(7L, "Ana García", "ana@mailinator.com", "2125550007");
        assertThat(mapper.toRow(mapper.toDocument(row, new SyncVersion(1, 1), Instant.now()))).isEqualTo(row);
    }

    @Test
    void the_field_names_are_the_applications() {
        assertThat(MemberDocumentFields.COLLECTION).isEqualTo(MemberDocuments.COLLECTION);
        assertThat(MemberDocumentFields.ID).isEqualTo(MemberDocuments.ID);
        assertThat(MemberDocumentFields.NAME).isEqualTo(MemberDocuments.NAME);
        assertThat(MemberDocumentFields.EMAIL).isEqualTo(MemberDocuments.EMAIL);
        assertThat(MemberDocumentFields.PHONE_NUMBER).isEqualTo(MemberDocuments.PHONE_NUMBER);
        assertThat(MemberDocumentFields.SCHEMA_VERSION).isEqualTo(MemberDocuments.SCHEMA_VERSION);
        assertThat(MemberDocumentFields.QUALITY).isEqualTo(MemberDocuments.QUALITY);
        assertThat(MemberDocumentFields.SYNC).isEqualTo(MemberDocuments.SYNC);
        assertThat(MemberDocumentFields.CURRENT_SCHEMA_VERSION).isEqualTo(MemberDocuments.CURRENT_SCHEMA_VERSION);
    }
}
