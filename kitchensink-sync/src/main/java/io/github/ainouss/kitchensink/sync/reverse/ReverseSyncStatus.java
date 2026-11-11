package io.github.ainouss.kitchensink.sync.reverse;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Counters for the reverse stream since the worker started. Thread-safe. */
public class ReverseSyncStatus {

    public enum State { STOPPED, RUNNING, FAILED }

    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final AtomicLong upserted = new AtomicLong();
    private final AtomicLong deleted = new AtomicLong();
    private final AtomicLong echoesSkipped = new AtomicLong();
    private final AtomicLong quarantined = new AtomicLong();
    private final AtomicLong batches = new AtomicLong();
    private final AtomicReference<Instant> lastBatchAt = new AtomicReference<>();
    private final Deque<Long> unseenProbes = new ConcurrentLinkedDeque<>();
    private final AtomicLong lastProbeLatencyMillis = new AtomicLong(-1);

    void running() {
        failure.set(null);
        state.set(State.RUNNING);
    }

    void stopped() {
        state.set(State.STOPPED);
        unseenProbes.clear();
    }

    void failed(String why) {
        failure.set(why);
        state.set(State.FAILED);
        unseenProbes.clear();
    }

    void batch(LegacyWriter.Written written, int echoes) {
        batches.incrementAndGet();
        upserted.addAndGet(written.upserted());
        deleted.addAndGet(written.deleted());
        quarantined.addAndGet(written.quarantined());
        echoesSkipped.addAndGet(echoes);
        lastBatchAt.set(Instant.now());
    }

    void probeSent(long sentAt) {
        unseenProbes.addLast(sentAt);
    }

    void probeSeen(long sentAt, long now) {
        while (!unseenProbes.isEmpty() && unseenProbes.peekFirst() <= sentAt) {
            unseenProbes.pollFirst();
        }
        lastProbeLatencyMillis.set(Math.max(0, now - sentAt));
    }

    /** End-to-end lag from MongoDB to PostgreSQL, measured with probes, or null if unknown. */
    public Double lagSeconds(long now) {
        if (state.get() != State.RUNNING) {
            return null;
        }
        long latency = lastProbeLatencyMillis.get();
        Long oldestUnseen = unseenProbes.peekFirst();
        long lag = Math.max(latency, oldestUnseen == null ? -1 : now - oldestUnseen);
        return lag < 0 ? null : lag / 1000.0;
    }

    public State state() {
        return state.get();
    }

    public String failure() {
        return failure.get();
    }

    public long upserted() {
        return upserted.get();
    }

    public long deleted() {
        return deleted.get();
    }

    public long echoesSkipped() {
        return echoesSkipped.get();
    }

    public long quarantined() {
        return quarantined.get();
    }

    public long batches() {
        return batches.get();
    }

    public Instant lastBatchAt() {
        return lastBatchAt.get();
    }
}
