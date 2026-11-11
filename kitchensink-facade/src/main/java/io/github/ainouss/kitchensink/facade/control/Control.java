package io.github.ainouss.kitchensink.facade.control;

import java.time.Instant;

/**
 * Who answers reads and who takes writes, as stored in the {@code migration_control} document.
 *
 * @param reads     who answers reads, and whether the other side is compared in the background
 * @param writes    who takes writes, or nobody for a few seconds during the switch
 * @param updatedAt when it last changed
 * @param updatedBy who changed it
 */
public record Control(Reads reads, Writes writes, Instant updatedAt, String updatedBy) {

    /** The primary side answers. In a shadow mode the other side is also called and compared, but never waited for. */
    public enum Reads {
        LEGACY, SHADOW_LEGACY, SHADOW_NEW, NEW;

        public Side primary() {
            return this == LEGACY || this == SHADOW_LEGACY ? Side.LEGACY : Side.NEW;
        }

        /** The side compared in the background, or null. */
        public Side shadow() {
            return switch (this) {
                case SHADOW_LEGACY -> Side.NEW;
                case SHADOW_NEW -> Side.LEGACY;
                default -> null;
            };
        }
    }

    /** One side takes writes, or none while {@code PAUSED} (503 with Retry-After). */
    public enum Writes { LEGACY, PAUSED, NEW }

    public enum Side { LEGACY, NEW }

    /** Used until a control document exists: the legacy handles everything. */
    public static final Control INITIAL = new Control(Reads.LEGACY, Writes.LEGACY, Instant.EPOCH, "default");
}
