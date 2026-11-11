package io.github.ainouss.kitchensink.sync.member;

import java.util.Comparator;

/**
 * The source log position of a change: commit position first, then the change's own position. Each document stores
 * it, so a replayed or late change is ignored. The commit position comes first because a change's own position can
 * be older than the snapshot even when it committed later. Snapshot rows use {@link #INITIAL_SNAPSHOT}, below every
 * streamed change.
 */
public record SyncVersion(long txLsn, long lsn) implements Comparable<SyncVersion> {

    public static final SyncVersion INITIAL_SNAPSHOT = new SyncVersion(0, 0);

    private static final Comparator<SyncVersion> ORDER =
            Comparator.comparingLong(SyncVersion::txLsn).thenComparingLong(SyncVersion::lsn);

    @Override
    public int compareTo(SyncVersion other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return txLsn + "/" + lsn;
    }
}
