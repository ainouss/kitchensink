package io.github.ainouss.kitchensink.local;

import java.nio.file.Files;

import tools.jackson.databind.JsonNode;

/**
 * The write switch, and its rollback. Every step is checked.
 *
 * <p>Cutover: until the reverse stream runs, a failure gives the writes back to the legacy; after that, it rolls back.
 * Rollback: nothing written since the switch is lost (the reverse stream copied it into the legacy). If a step fails,
 * writes stay paused on purpose: fix the cause and run it again, it picks up where it stopped.
 */
final class Switch {

    static final int MAX_LAG_SECONDS = 5;
    static final int DRAIN_TIMEOUT_SECONDS = 30;
    static final int START_TIMEOUT_SECONDS = 30;

    private final Workspace workspace;
    private final Api api;
    private final Console console = new Console();

    long pausedMillis;
    long members;

    Switch(Workspace workspace) throws Exception {
        this.workspace = workspace;
        this.api = new Api(workspace);
    }

    /**
     * Legacy -> new application. Returns true when the new application owns the writes.
     * {@code yes} answers the question; {@code rehearsal} is set by ks rehearse, which is the rehearsal itself.
     */
    boolean cutover(boolean yes, boolean rehearsal, int minShadowCompared, int watchSeconds) {
        console.title("Cutover: the legacy hands the writes to the new application");

        console.step("1  check: the legacy owns the writes");
        JsonNode control;
        try {
            control = api.control();
        } catch (Api.ApiException e) {
            console.failed("the facade is not running (ks start)");
            return false;
        }
        String reads = control.path("reads").asString();
        if (!control.path("writes").asString().equals("LEGACY")) {
            console.failed("writes are " + control.path("writes").asString() + ", not LEGACY");
            return false;
        }
        console.ok("reads " + reads);

        console.step("1  check: copy running, lag < " + MAX_LAG_SECONDS + " s");
        // A busy machine can lag for a moment: give the copy up to 30 s to catch up before refusing.
        long lagDeadline = System.currentTimeMillis() + 30_000;
        double lag;
        while (true) {
            JsonNode status = api.syncStatus();
            String forward = status.path("forward").path("state").asString();
            String reverse = status.path("reverse").path("state").asString();
            lag = status.path("forward").path("lagSeconds").asDouble(Double.MAX_VALUE);
            if (!forward.equals("RUNNING") || !reverse.equals("STOPPED")) {
                console.failed("forward " + forward + ", reverse " + reverse);
                return false;
            }
            if (lag < MAX_LAG_SECONDS) {
                break;
            }
            if (System.currentTimeMillis() > lagDeadline) {
                console.failed("lag " + lag + " s for 30 s (still copying, or the machine is too busy)");
                return false;
            }
            Api.sleep(2_000);
        }
        console.ok("lag " + lag + " s");

        console.step("1  check: dual-run, " + minShadowCompared + " reads compared, 0 mismatched");
        JsonNode totals = api.shadowTotals();
        long mismatched = totals.path("MISMATCHED").asLong();
        long compared = totals.path("MATCHED").asLong() + totals.path("ACCEPTED").asLong();
        if (mismatched > 0) {
            console.failed(mismatched + " mismatches: see GET /facade/shadow");
            return false;
        }
        if (compared < minShadowCompared) {
            console.failed("only " + compared + " reads compared: run ks dual-run");
            return false;
        }
        console.ok(compared + " compared, 0 mismatched");

        console.step("1  check: reconciliation green");
        JsonNode report = api.reconcile().orElse(null);
        if (report == null || !report.path("green").asBoolean()) {
            console.failed(report == null ? "did not run" : "red: " + report.path("counts"));
            return false;
        }
        console.ok(String.format("%,d members", report.path("legacyCount").asLong()));

        if (!rehearsal) {
            // Optional: a rehearsal measures the pause and proves the way back on this machine first.
            console.line(Files.exists(workspace.rehearsedMarker())
                    ? "The switch and the way back were rehearsed on this machine (.local/rehearsals/)."
                    : "No rehearsal on this machine. Optional: ks rehearse measures the pause and the way back first.");
        }

        if (!console.confirm("All checks are green. Pause the writes and switch to the new application?", yes)) {
            console.line("No go.");
            return false;
        }

        console.step("2  pause: writes refused (503 + Retry-After)");
        long pausedAt = System.currentTimeMillis();
        try {
            api.control(reads, "PAUSED", "ks cutover");
        } catch (Api.ApiException e) {
            console.failed(e.getMessage());
            resume(reads);
            return false;
        }
        if (!api.writesRefusedWith503()) {
            console.failed("writes are not refused");
            resume(reads);
            return false;
        }
        console.ok();

        console.step("3  catch up: every legacy commit is in MongoDB");
        try {
            JsonNode drain = api.post(api.sync + "/sync/forward/drain?timeoutSeconds=" + DRAIN_TIMEOUT_SECONDS);
            console.ok(drain.path("tookMillis").asLong() + " ms to drain");
        } catch (Api.ApiException e) {
            console.failed(e.getMessage());
            resume(reads);
            return false;
        }

        console.step("3  catch up: both databases identical (counts, hashes)");
        report = api.reconcile().orElse(null);
        if (report == null || !report.path("green").asBoolean()
                || !report.path("legacyDigest").asString().equals(report.path("mongoDigest").asString())) {
            console.failed("not identical" + (report == null ? "" : ": " + report.path("counts")));
            resume(reads);
            return false;
        }
        members = report.path("legacyCount").asLong();
        console.ok(String.format("%,d members, digest %s", members,
                report.path("legacyDigest").asString().substring(0, 12)));

        console.step("4  flip: copy to MongoDB stopped, ids above the legacy's");
        try {
            api.post(api.sync + "/sync/forward/stop");
            JsonNode sequence = api.post(api.sync + "/sync/target-sequence");
            console.ok("next id above " + sequence.path("mongodbNextIdAbove").asLong());
        } catch (Api.ApiException e) {
            console.failed(e.getMessage());
            resume(reads);
            return false;
        }

        console.step("4  flip: copy back started (MongoDB -> legacy)");
        try {
            api.post(api.sync + "/sync/reverse/start?from=now");
            console.ok();
        } catch (Api.ApiException e) {
            console.failed(e.getMessage());
            resume(reads);
            return false;
        }

        console.step("4  flip: reads and writes go to the new application");
        try {
            api.control("NEW", "NEW", "ks cutover");
        } catch (Api.ApiException e) {
            console.failed(e.getMessage());
            return rollbackAfterFailure();
        }
        pausedMillis = System.currentTimeMillis() - pausedAt;
        console.ok("writes were paused for " + pausedMillis + " ms");

        String smoke = "switch-smoke-" + System.currentTimeMillis() + "@mailinator.com";
        console.step("5  watch: smoke test, a registration through the facade");
        if (api.register("Switch Smoke", smoke) != 200) {
            console.failed("refused");
            return rollbackAfterFailure();
        }
        console.ok(smoke);

        // Writes are open again, so "green" is the check here; "identical" only holds while writes are paused.
        console.step("5  watch: smoke test, in MongoDB and back in the legacy");
        try {
            api.post(api.sync + "/sync/reverse/drain?timeoutSeconds=" + DRAIN_TIMEOUT_SECONDS);
        } catch (Api.ApiException e) {
            console.failed(e.getMessage());
            return rollbackAfterFailure();
        }
        report = api.reconcile().orElse(null);
        if (report == null || !report.path("green").asBoolean()) {
            console.failed("not on both sides");
            return rollbackAfterFailure();
        }
        console.ok(String.format("%,d members", report.path("legacyCount").asLong()));

        console.step("5  watch: " + watchSeconds + " s, copy back running, lag < " + MAX_LAG_SECONDS + " s");
        long until = System.currentTimeMillis() + watchSeconds * 1000L;
        double reverseLag = 0;
        while (System.currentTimeMillis() < until) {
            try {
                JsonNode r = api.syncStatus().path("reverse");
                reverseLag = r.path("lagSeconds").asDouble(0);
                if (!r.path("state").asString().equals("RUNNING") || reverseLag >= MAX_LAG_SECONDS) {
                    console.failed("reverse " + r.path("state").asString() + ", lag " + reverseLag + " s");
                    return rollbackAfterFailure();
                }
            } catch (Api.ApiException e) {
                console.failed("sync unreachable");
                return rollbackAfterFailure();
            }
            Api.sleep(2_000);
        }
        console.ok("reverse lag " + reverseLag + " s");

        console.title("Done: the new application owns the members. Writes were paused for " + pausedMillis + " ms.");
        return true;
    }

    /** New application -> legacy. Returns true when the legacy owns the writes again. */
    boolean rollback(boolean yes) {
        console.title("Rollback: the new application hands the writes back to the legacy");
        String reads;
        try {
            reads = api.control().path("reads").asString();
        } catch (Api.ApiException e) {
            console.failed("the facade is not running (ks start)");
            return false;
        }
        if (!console.confirm("Roll back: pause the writes and give them back to the legacy?", yes)) {
            console.line("No rollback.");
            return false;
        }

        console.step("1  pause: writes refused (503 + Retry-After)");
        long pausedAt = System.currentTimeMillis();
        try {
            api.control(reads, "PAUSED", "ks rollback");
        } catch (Api.ApiException e) {
            console.failed(e.getMessage());
            return false;
        }
        if (!api.writesRefusedWith503()) {
            console.failed("writes are not refused");
            return false;
        }
        console.ok();

        // On a rerun after a failure, the reverse sync may be stopped: start it again from where it stopped.
        String reverse = api.syncStatus().path("reverse").path("state").asString();
        if (!reverse.equals("RUNNING")) {
            console.step("2  catch up: copy back " + reverse + ", started again where it stopped");
            try {
                try {
                    api.post(api.sync + "/sync/forward/stop");
                } catch (Api.ApiException ignored) {
                    // already stopped
                }
                api.post(api.sync + "/sync/reverse/start?from=resume");
                console.ok();
            } catch (Api.ApiException e) {
                console.failed("it does not start; writes stay paused. See /sync/status");
                return false;
            }
        }

        console.step("2  catch up: every MongoDB write is in the legacy");
        try {
            JsonNode drain = api.post(api.sync + "/sync/reverse/drain?timeoutSeconds=" + DRAIN_TIMEOUT_SECONDS);
            console.ok(drain.path("tookMillis").asLong() + " ms to drain");
        } catch (Api.ApiException e) {
            console.failed(e.getMessage() + "; writes stay paused");
            return false;
        }

        console.step("3  flip back: legacy ids start above every new id");
        try {
            JsonNode sequence = api.post(api.sync + "/sync/legacy-sequence");
            console.ok("member_seq " + sequence.path("memberSeq").asLong());
        } catch (Api.ApiException e) {
            console.failed(e.getMessage() + "; writes stay paused");
            return false;
        }

        console.step("3  flip back: copy back stopped");
        try {
            api.post(api.sync + "/sync/reverse/stop");
            console.ok();
        } catch (Api.ApiException e) {
            console.failed(e.getMessage() + "; writes stay paused");
            return false;
        }

        console.step("3  flip back: copy to MongoDB started and drained");
        try {
            api.post(api.sync + "/sync/forward/start");
            api.post(api.sync + "/sync/forward/drain?timeoutSeconds=" + DRAIN_TIMEOUT_SECONDS);
            console.ok();
        } catch (Api.ApiException e) {
            console.failed(e.getMessage() + "; writes stay paused");
            return false;
        }

        console.step("3  flip back: both databases identical");
        JsonNode report = api.reconcile().orElse(null);
        if (report == null
                || !report.path("legacyDigest").asString().equals(report.path("mongoDigest").asString())) {
            console.failed("not identical; writes stay paused. See GET /sync/reconcile/latest");
            return false;
        }
        members = report.path("legacyCount").asLong();
        console.ok(String.format("%,d members", members));

        console.step("3  flip back: reads compared, writes to the legacy");
        try {
            api.control("SHADOW_LEGACY", "LEGACY", "ks rollback");
        } catch (Api.ApiException e) {
            console.failed(e.getMessage());
            return false;
        }
        pausedMillis = System.currentTimeMillis() - pausedAt;
        console.ok("writes were paused for " + pausedMillis + " ms");

        String smoke = "rollback-smoke-" + System.currentTimeMillis() + "@mailinator.com";
        console.step("4  watch: smoke test, a registration in both databases");
        try {
            if (api.register("Rollback Smoke", smoke) != 200) {
                throw new Api.ApiException(0, "refused");
            }
            api.post(api.sync + "/sync/forward/drain?timeoutSeconds=" + DRAIN_TIMEOUT_SECONDS);
            report = api.reconcile().orElse(null);
            if (report == null || !report.path("green").asBoolean()) {
                throw new Api.ApiException(0, "not on both sides");
            }
            console.ok(smoke);
        } catch (Api.ApiException e) {
            console.failed(e.getMessage());
            return false;
        }

        console.title("Done: the legacy owns the members again. Writes were paused for " + pausedMillis + " ms.");
        return true;
    }

    private boolean rollbackAfterFailure() {
        console.line("-> rolling back");
        rollback(true);
        return false;
    }

    /** Before the reverse sync runs: gives the writes back to the legacy, and checks the copy runs again. */
    private void resume(String reads) {
        console.line("-> giving the writes back to the legacy");
        for (String call : new String[] {"/sync/reverse/stop", "/sync/forward/start"}) {
            try {
                api.post(api.sync + call);
            } catch (Api.ApiException ignored) {
                // checked below
            }
        }
        try {
            api.control(reads, "LEGACY", "ks cutover");
        } catch (Api.ApiException e) {
            console.line("!! writes not given back: PUT /facade/control {\"writes\":\"LEGACY\"} by hand");
        }
        long deadline = System.currentTimeMillis() + START_TIMEOUT_SECONDS * 1000L;
        String forward = "";
        String reverse = "";
        while (true) {
            try {
                JsonNode status = api.syncStatus();
                forward = status.path("forward").path("state").asString();
                reverse = status.path("reverse").path("state").asString();
            } catch (Api.ApiException e) {
                forward = "unknown";
            }
            if (forward.equals("RUNNING") || System.currentTimeMillis() > deadline) {
                break;
            }
            Api.sleep(1_000);
        }
        if (!forward.equals("RUNNING")) {
            console.line("!! the copy is " + forward + ", not RUNNING: POST /sync/forward/start");
        }
        if (!reverse.equals("STOPPED")) {
            console.line("!! the reverse sync is not STOPPED: POST /sync/reverse/stop");
        }
    }
}
