package io.github.ainouss.kitchensink.sync.forward;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.Test;

import io.github.ainouss.kitchensink.sync.member.SyncVersion;

/** Checks how Debezium events become a {@link SyncVersion}, used to order changes. */
class ChangeEventsTests {

    private static final Schema SOURCE = SchemaBuilder.struct()
            .field("lsn", Schema.OPTIONAL_INT64_SCHEMA)
            .field("sequence", Schema.OPTIONAL_STRING_SCHEMA)
            .field("snapshot", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    @Test
    void a_streamed_change_is_ordered_by_its_sequences_commit_lsn_then_its_own_lsn() {
        assertThat(ChangeEvents.version(source(880L, "[\"900\",\"880\"]", "false")))
                .isEqualTo(new SyncVersion(900, 880));
        assertThat(ChangeEvents.version(source(50L, "[null,\"50\"]", null))).isEqualTo(new SyncVersion(0, 50));
    }

    @Test
    void the_initial_snapshot_sits_below_every_streamed_change_an_incremental_one_does_not() {
        SyncVersion snapshot = ChangeEvents.version(source(1000L, "[null,\"1000\"]", "last"));
        SyncVersion firstStreamed = ChangeEvents.version(source(50L, "[null,\"50\"]", "false"));

        assertThat(snapshot).isEqualTo(SyncVersion.INITIAL_SNAPSHOT);
        assertThat(snapshot).isLessThan(firstStreamed);
        assertThat(ChangeEvents.version(source(1200L, "[\"1100\",\"1200\"]", "incremental")))
                .isEqualTo(new SyncVersion(1100, 1200));
    }

    private static Struct source(Long lsn, String sequence, String snapshot) {
        Struct source = new Struct(SOURCE).put("lsn", lsn).put("sequence", sequence);
        return snapshot == null ? source : source.put("snapshot", snapshot);
    }
}
