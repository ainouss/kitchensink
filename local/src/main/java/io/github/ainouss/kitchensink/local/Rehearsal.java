package io.github.ainouss.kitchensink.local;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * {@code ks rehearse}: switch, let the new application own the writes for a while, roll back, all under live
 * traffic. It proves the two numbers the switch is decided on: how long writes pause, and that no registration a
 * client was told succeeded is lost.
 */
final class Rehearsal {

    private final Workspace workspace;
    private final Api api;
    private final Console console = new Console();

    Rehearsal(Workspace workspace) throws Exception {
        this.workspace = workspace;
        this.api = new Api(workspace);
    }

    boolean run(int trafficSeconds, int newSeconds) throws Exception {
        String run = "rehearsal-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        console.title("Rehearsal: switch, " + newSeconds + " s on the new application, rollback, under traffic");

        console.step("0  before: reconciliation green, reads compared");
        JsonNode report = api.reconcile().orElse(null);
        if (report == null || !report.path("green").asBoolean()) {
            console.failed("not green before the rehearsal");
            return false;
        }
        long before = report.path("legacyCount").asLong();
        api.control("SHADOW_LEGACY", "LEGACY", "ks rehearse");
        console.ok(String.format("%,d members", before));

        Traffic traffic = new Traffic(api.facade, run, before, 4, 2).start(trafficSeconds, 15);
        Thread.sleep(15_000);

        Switch cutover = new Switch(workspace);
        if (!cutover.cutover(true, true, 100, 10)) {
            traffic.await();
            return false;
        }
        console.line("the new application owns the writes for " + newSeconds + " s, under traffic");
        Thread.sleep(newSeconds * 1000L);
        Switch rollback = new Switch(workspace);
        if (!rollback.rollback(true)) {
            traffic.await();
            return false;
        }

        console.step("   traffic until its end");
        Map<String, Object> summary = traffic.await();
        console.ok();

        console.step("9  after: identical, every confirmed registration kept");
        api.post(api.sync + "/sync/forward/drain?timeoutSeconds=" + Switch.DRAIN_TIMEOUT_SECONDS);
        report = api.reconcile().orElseThrow();
        long after = report.path("legacyCount").asLong();
        boolean identical = report.path("legacyDigest").asString().equals(report.path("mongoDigest").asString());
        long confirmed = traffic.writesConfirmed();
        long lost = before + confirmed + 2 - after; // the traffic's registrations and the two smoke tests
        long errors = traffic.errorCount();
        boolean passed = identical && lost == 0 && errors == 0;
        if (passed) {
            console.ok(String.format("%,d = %,d + %,d + 2 smoke tests", after, before, confirmed));
        } else {
            console.failed("identical " + identical + ", lost " + lost + ", client errors " + errors);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run);
        result.put("passed", passed);
        result.put("cutoverPausedMillis", cutover.pausedMillis);
        result.put("rollbackPausedMillis", rollback.pausedMillis);
        result.put("membersBefore", before);
        result.put("membersAfter", after);
        result.put("registrationsLost", lost);
        result.put("identical", identical);
        result.put("traffic", summary);
        Files.createDirectories(workspace.rehearsals());
        Files.writeString(workspace.rehearsals().resolve(run + ".json"),
                Api.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));

        System.out.println();
        System.out.println("== Rehearsal " + (passed ? "passed" : "FAILED"));
        System.out.printf("   writes paused   cutover %,d ms, rollback %,d ms%n", cutover.pausedMillis,
                rollback.pausedMillis);
        System.out.printf("   traffic         %,d reads, %,d registrations confirmed, %d retries after a 503,%n",
                summary.get("reads"), summary.get("writesConfirmed"), summary.get("pausedRetries"));
        System.out.printf("                   slowest registration %,d ms, %d errors%n",
                summary.get("maxWriteMillis"), errors);
        System.out.printf("   data            %,d -> %,d members, %d lost, stores identical: %s%n", before, after,
                lost, identical);
        System.out.println("   saved           .local/rehearsals/" + run + ".json");
        if (passed) {
            Files.writeString(workspace.rehearsedMarker(), run);
        }
        return passed;
    }
}
