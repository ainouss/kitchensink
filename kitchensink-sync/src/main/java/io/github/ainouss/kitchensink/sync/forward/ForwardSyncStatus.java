package io.github.ainouss.kitchensink.sync.forward;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.github.ainouss.kitchensink.sync.member.SyncVersion;

/** Counters for the forward stream since the worker started, for the status API and metrics. Thread-safe. */
public class ForwardSyncStatus {

    public enum State { STOPPED, STARTING, RUNNING, FAILED }

    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final AtomicLong snapshotRows = new AtomicLong();
    private final AtomicLong upserted = new AtomicLong();
    private final AtomicLong deleted = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong quarantined = new AtomicLong();
    private final AtomicLong batches = new AtomicLong();
    private final AtomicReference<SyncVersion> lastVersion = new AtomicReference<>();
    private final AtomicReference<Instant> lastSourceCommit = new AtomicReference<>();
    private final AtomicReference<Instant> lastBatchAt = new AtomicReference<>();
    private final Deque<Long> unseenProbes = new ConcurrentLinkedDeque<>();
    private final AtomicLong lastProbeLatencyMillis = new AtomicLong(-1);
    private final AtomicReference<LegacyWal.SlotState> slot = new AtomicReference<>();

    void starting() {
        failure.set(null);
        state.set(State.STARTING);
    }

    void running() {
        state.compareAndSet(State.STARTING, State.RUNNING);
    }

    void stopped() {
        state.set(State.STOPPED);
        unseenProbes.clear();
    }

    /** A probe was written at {@code sentAt}. */
    void probeSent(long sentAt) {
        unseenProbes.addLast(sentAt);
    }

    /** The probe written at {@code sentAt} came through, and everything before it was applied. */
    void probeSeen(long sentAt, long now) {
        while (!unseenProbes.isEmpty() && unseenProbes.peekFirst() <= sentAt) {
            unseenProbes.pollFirst();
        }
        lastProbeLatencyMillis.set(Math.max(0, now - sentAt));
    }

    void slot(LegacyWal.SlotState state) {
        slot.set(state);
    }

    /**
     * End-to-end lag: how long the last probe took, or how long the oldest pending probe has waited if that is longer.
     * Null when the stream is not running or before the first probe.
     */
    public Double lagSeconds(long now) {
        if (state.get() != State.RUNNING) {
            return null;
        }
        long latency = lastProbeLatencyMillis.get();
        Long oldestUnseen = unseenProbes.peekFirst();
        long waited = oldestUnseen == null ? -1 : now - oldestUnseen;
        long lag = Math.max(latency, waited);
        return lag < 0 ? null : lag / 1000.0;
    }

    /** The replication slot as last read, or null. */
    public LegacyWal.SlotState slot() {
        return slot.get();
    }

    void failed(String why) {
        failure.set(why);
        state.set(State.FAILED);
    }

    void batch(MongoMemberSink.ApplyResult result, long snapshotRowsInBatch, SyncVersion last, Instant lastCommit) {
        running();
        batches.incrementAndGet();
        snapshotRows.addAndGet(snapshotRowsInBatch);
        upserted.addAndGet(result.upserted());
        deleted.addAndGet(result.deleted());
        skipped.addAndGet(result.skipped());
        quarantined.addAndGet(result.quarantined());
        if (last != null) {
            lastVersion.set(last);
        }
        if (lastCommit != null) {
            lastSourceCommit.set(lastCommit);
        }
        lastBatchAt.set(Instant.now());
    }

    public State state() {
        return state.get();
    }

    public String failure() {
        return failure.get();
    }

    public long snapshotRows() {
        return snapshotRows.get();
    }

    public long upserted() {
        return upserted.get();
    }

    public long deleted() {
        return deleted.get();
    }

    public long skipped() {
        return skipped.get();
    }

    public long quarantined() {
        return quarantined.get();
    }

    public long batches() {
        return batches.get();
    }

    /** The newest change applied, or null. */
    public SyncVersion lastVersion() {
        return lastVersion.get();
    }

    /** When the legacy database committed the newest change applied. */
    public Instant lastSourceCommit() {
        return lastSourceCommit.get();
    }

    public Instant lastBatchAt() {
        return lastBatchAt.get();
    }
}
