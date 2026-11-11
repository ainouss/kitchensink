package io.github.ainouss.kitchensink.local;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/** The {@code ks} command: runs the whole migration on one machine. */
public final class Ks {

    private static final String HELP = """
            Usage: ks <command> [options]

              start                        start everything (Ctrl+C stops it). First start: 10,000 legacy members
              reset                        delete the data: the next start creates it again
              sync                         the initial load into MongoDB, then every change (legacy -> MongoDB)
              rules [legacy|new]           play the legacy's rules (21 recorded cases) through the facade, or on one app
              status                       who owns the data, and the state of the copy
              dual-run [--seconds 30]      send reads to both systems and compare the answers
              reconcile                    compare every record in both stores
              rehearse                     switch and roll back under live traffic, and check nothing was lost
              cutover [--yes] [--watch 30] switch the writes to the new application
              rollback [--yes]             give the writes back to the legacy
              traffic [--seconds 60]       readers and writers through the facade, as real clients
              stop                         stop what "ks start" runs, even if its window was closed

            Run sync to traffic in a second terminal while "ks start" runs.
            """;

    private Ks() {
    }

    public static void main(String[] args) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.US); // plain ASCII numbers in any console
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        System.setProperty("org.slf4j.simpleLogger.log.de.flapdoodle.embed.mongo.runtime.Mongod", "off");
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            System.out.print(HELP);
            return;
        }
        Workspace workspace = Workspace.find();
        List<String> options = Arrays.asList(args).subList(1, args.length);
        boolean ok = true;
        try {
            switch (args[0]) {
                case "start" -> new Stack(workspace).start();
                case "reset" -> new Stack(workspace).reset();
                case "sync" -> ok = new StartSync(workspace).run();
                case "rules" -> ok = new Rules(workspace).run(options.isEmpty() ? "facade" : options.getFirst());
                case "status" -> status(new Api(workspace));
                case "dual-run" -> dualRun(synced(new Api(workspace)), (int) number(options, "--seconds", 30));
                case "reconcile" -> ok = reconcile(synced(new Api(workspace)));
                case "rehearse" -> {
                    synced(new Api(workspace));
                    ok = new Rehearsal(workspace).run(90, 20);
                }
                case "cutover" -> ok = new Switch(workspace).cutover(options.contains("--yes"), false, 1000,
                        (int) number(options, "--watch", 30));
                case "rollback" -> ok = new Switch(workspace).rollback(options.contains("--yes"));
                case "traffic" -> ok = traffic(new Api(workspace), (int) number(options, "--seconds", 60),
                        (int) number(options, "--writers", 2));
                case "stop" -> stop(workspace);
                default -> {
                    System.out.print(HELP);
                    ok = false;
                }
            }
        } catch (Api.ApiException e) {
            System.out.println("The services do not answer (" + e.getMessage() + "). Is \"ks start\" running?");
            ok = false;
        } catch (IllegalStateException e) {
            System.out.println(e.getMessage());
            ok = false;
        }
        if (!ok) {
            System.exit(1);
        }
    }

    /** dual-run, reconcile and rehearse compare both stores: they need the copy to run. */
    private static Api synced(Api api) {
        JsonNode sync = api.syncStatus();
        if (!StartSync.state(sync, "forward").equals("RUNNING") && !StartSync.state(sync, "reverse").equals("RUNNING")) {
            throw new IllegalStateException("MongoDB is not loaded yet, or the copy is stopped: run ks sync first.");
        }
        return api;
    }

    private static void stop(Workspace workspace) throws java.io.IOException {
        List<String> stopped = Leftovers.stop(workspace);
        System.out.println(stopped.isEmpty() ? "Nothing to stop." : "Stopped " + String.join(", ", stopped) + ".");
    }

    private static void status(Api api) {
        JsonNode control = api.control();
        JsonNode sync = api.syncStatus();
        JsonNode forward = sync.path("forward");
        JsonNode reverse = sync.path("reverse");
        JsonNode totals = api.shadowTotals();
        System.out.println();
        System.out.println("  reads go to      " + control.path("reads").asString());
        System.out.println("  writes go to     " + control.path("writes").asString());
        System.out.println("  copy             legacy -> MongoDB " + forward.path("state").asString()
                + lag(forward) + ", MongoDB -> legacy " + reverse.path("state").asString() + lag(reverse));
        System.out.println("  dual-run         " + totals.path("MATCHED").asLong() + " matched, "
                + totals.path("MISMATCHED").asLong() + " mismatched, " + totals.path("ACCEPTED").asLong()
                + " accepted differences");
        JsonNode last = sync.path("lastReconciliation");
        if (!last.isMissingNode() && !last.isNull()) {
            System.out.println("  reconciliation   " + (last.path("green").asBoolean() ? "green" : "RED") + " at "
                    + last.path("startedAt").asString());
        }
    }

    private static String lag(JsonNode stream) {
        JsonNode lag = stream.path("lagSeconds");
        return lag.isNumber() ? String.format(" (lag %.1f s)", lag.asDouble()) : "";
    }

    private static boolean reconcile(Api api) {
        JsonNode report = api.reconcile().orElseThrow(() -> new IllegalStateException("The reconciliation did not run."));
        boolean identical = report.path("legacyDigest").asString().equals(report.path("mongoDigest").asString());
        System.out.printf("%n  legacy    %,d members   hash %s%n", report.path("legacyCount").asLong(),
                report.path("legacyDigest").asString());
        System.out.printf("  MongoDB   %,d members   hash %s%n", report.path("mongoCount").asLong(),
                report.path("mongoDigest").asString());
        System.out.printf("  %s in %,d ms%s%n", report.path("green").asBoolean() ? "green" : "RED",
                report.path("durationMillis").asLong(),
                identical ? ", identical" : ", " + report.path("inFlight").asLong()
                        + " records in flight (writes are open)");
        return report.path("green").asBoolean();
    }

    private static void dualRun(Api api, int seconds) throws InterruptedException {
        JsonNode control = api.control();
        api.control("SHADOW_LEGACY", control.path("writes").asString(), "ks dual-run");
        System.out.println();
        System.out.println("  Reads now go to both systems: the legacy answers, the new application's answer is "
                + "compared.");
        System.out.println("  Sending reads for " + seconds + " s...");
        new Traffic(api.facade, "dual-run", highestId(), 4, 0).start(seconds, 0).await();
        JsonNode totals = api.shadowTotals();
        System.out.println("  compared " + (totals.path("MATCHED").asLong() + totals.path("ACCEPTED").asLong())
                + " reads: " + totals.path("MISMATCHED").asLong() + " mismatched");
    }

    private static boolean traffic(Api api, int seconds, int writers) throws InterruptedException {
        System.out.println("  " + seconds + " s of traffic through the facade: 4 readers, " + writers + " writers...");
        Map<String, Object> summary = new Traffic(api.facade, "traffic-" + System.currentTimeMillis(), highestId(), 4,
                writers).start(seconds, 15).await();
        System.out.println("  " + summary);
        return (long) summary.get("readErrors") + (long) summary.get("writeErrors")
                + (long) summary.get("writesAbandoned") == 0;
    }

    /** The highest member id in the legacy database, so the readers ask for members that exist. */
    private static long highestId() {
        String url = "jdbc:postgresql://localhost:" + Workspace.POSTGRES_PORT + "/" + LegacyDatabase.DATABASE;
        try (var connection = java.sql.DriverManager.getConnection(url, "postgres", "postgres");
             var rs = connection.createStatement().executeQuery("SELECT coalesce(max(id), 0) FROM member")) {
            rs.next();
            return Math.max(1, rs.getLong(1));
        } catch (java.sql.SQLException e) {
            return Stack.MEMBERS;
        }
    }

    private static long number(List<String> options, String name, long fallback) {
        int at = options.indexOf(name);
        return at >= 0 && at + 1 < options.size() ? Long.parseLong(options.get(at + 1)) : fallback;
    }
}
