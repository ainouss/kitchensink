package io.github.ainouss.kitchensink.local;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.mongodb.client.MongoClient;
import tools.jackson.databind.JsonNode;

/**
 * {@code ks start}: the whole migration on one machine.
 * PostgreSQL with the legacy's data, MongoDB, the legacy on WildFly, the new application, the sync and the facade.
 * The copy into MongoDB is not started here: that is {@code ks sync}, a step of its own.
 * Everything stops with Ctrl+C. The data stays in .local/ for the next start.
 * What an earlier run left running (its window closed, say) is stopped first: see {@link Leftovers}.
 */
final class Stack {

    /** The legacy's generated members, after John Smith: enough to be real, small enough for the legacy's page. */
    static final int MEMBERS = 10_000;

    private final Workspace workspace;
    private final Console console = new Console();
    private final List<Process> processes = new ArrayList<>();
    private LegacyDatabase postgres;
    private LocalMongo mongo;
    private volatile boolean stopping;

    Stack(Workspace workspace) {
        this.workspace = workspace;
    }

    /**
     * {@code ks reset}: deletes the data, so the next start creates the same members again, and {@code ks sync} loads
     * them. The rehearsal reports are kept.
     */
    void reset() throws Exception {
        stopLeftovers();
        Leftovers.delete(workspace.postgresData());
        Leftovers.delete(workspace.mongoData());
        Leftovers.delete(workspace.logs());
        System.out.printf("Data deleted. The next \"ks start\" creates the %,d members again, and \"ks sync\" loads"
                + " them into MongoDB.%n", MEMBERS + 1);
    }

    void start() throws Exception {
        checkJars();
        checkPortsFree(stopLeftovers() ? 30 : 0); // Windows can hold a port a moment after its process stopped
        Files.createDirectories(workspace.logs());
        Leftovers.record(workspace, "ks", ProcessHandle.current());
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "ks-stop"));

        console.title("Starting kitchensink: the legacy, the new application, and the migration between them");
        console.step("PostgreSQL (the legacy's database)");
        postgres = LegacyDatabase.start(Workspace.POSTGRES_PORT, workspace.postgresData(),
                workspace.logs().resolve("postgres.log"));
        Leftovers.record(workspace, "postgres", postgres.process());
        console.ok("port " + Workspace.POSTGRES_PORT);
        if (postgres.created()) {
            console.step(String.format("legacy data: %,d members (first start only)", MEMBERS + 1));
            postgres.seed(MEMBERS);
            console.ok();
        }

        console.step("MongoDB (downloaded on first start)");
        mongo = LocalMongo.start(Workspace.MONGO_PORT, workspace.mongoData());
        Leftovers.record(workspace, "mongod", mongo.process());
        console.ok("port " + Workspace.MONGO_PORT);

        String mongoUri = mongo.uri("kitchensink");
        String token = workspace.adminToken();

        Process legacy = launch("legacy", workspace.jar("legacy", "kitchensink-legacy.jar"), "512m",
                Map.of("LEGACY_DB_URL", postgres.jdbcUrl(),
                        "LEGACY_DB_USER", LegacyDatabase.APP_USER,
                        "LEGACY_DB_PASSWORD", LegacyDatabase.APP_PASSWORD),
                List.of("-Djboss.socket.binding.port-offset=" + (Workspace.LEGACY_PORT - 8080)));
        launchAndWait("new application", "app", workspace.jar("kitchensink-app", "kitchensink-app-exec.jar"), "512m",
                Map.of("MONGODB_URI", mongoUri, "KITCHENSINK_SEED", "false", "PORT", "" + Workspace.APP_PORT),
                "http://localhost:" + Workspace.APP_PORT + "/actuator/health");
        launchAndWait("sync (waits for ks sync)", "sync",
                workspace.jar("kitchensink-sync", "kitchensink-sync-exec.jar"), "768m",
                Map.of("MONGODB_URI", mongoUri,
                        // Until "ks sync" starts a stream; then the stream it last ran restarts with it.
                        "SYNC_FORWARD_ENABLED", "false",
                        "LEGACY_JDBC_URL", postgres.jdbcUrl(),
                        "LEGACY_JDBC_USER", LegacyDatabase.SYNC_USER,
                        "LEGACY_JDBC_PASSWORD", LegacyDatabase.SYNC_PASSWORD,
                        "PORT", "" + Workspace.SYNC_PORT),
                "http://localhost:" + Workspace.SYNC_PORT + "/actuator/health");
        launchAndWait("facade (routes every request)", "facade",
                workspace.jar("kitchensink-facade", "kitchensink-facade-exec.jar"), "384m",
                Map.of("MONGODB_URI", mongoUri,
                        "LEGACY_URL", "http://localhost:" + Workspace.LEGACY_PORT,
                        "NEW_URL", "http://localhost:" + Workspace.APP_PORT,
                        "FACADE_ADMIN_TOKEN", token,
                        "PORT", "" + Workspace.FACADE_PORT),
                "http://localhost:" + Workspace.FACADE_PORT + "/actuator/health");
        console.step("legacy on WildFly");
        waitFor(legacy, "legacy", "http://localhost:" + Workspace.LEGACY_PORT + "/kitchensink/rest/members/0");
        console.ok("port " + Workspace.LEGACY_PORT);

        printUrls();
        printCopy();
        System.out.println();
        System.out.println("Running. Press Ctrl+C to stop everything. Logs are in .local/logs/.");
        while (true) {
            Thread.sleep(1_000);
            for (Process process : processes) {
                if (!process.isAlive() && !stopping) {
                    System.out.println("A service stopped unexpectedly: see .local/logs/. Stopping everything.");
                    return;
                }
            }
        }
    }

    /** Refuses while "ks start" runs; stops what an earlier run left running. True if something was stopped. */
    private boolean stopLeftovers() throws Exception {
        Leftovers.refuseIfRunning(workspace);
        List<String> leftovers = Leftovers.stop(workspace);
        if (!leftovers.isEmpty()) {
            System.out.println("Stopped what an earlier run left running: " + String.join(", ", leftovers));
        }
        return !leftovers.isEmpty();
    }

    private void printUrls() {
        System.out.println();
        System.out.println("  Through the facade (what users use)   http://localhost:" + Workspace.FACADE_PORT
                + "/kitchensink/");
        System.out.println("  The new application                    http://localhost:" + Workspace.APP_PORT + "/");
        System.out.println("  Its API documentation                  http://localhost:" + Workspace.APP_PORT
                + "/swagger-ui.html");
        System.out.println("  The legacy application                 http://localhost:" + Workspace.LEGACY_PORT
                + "/kitchensink/");
        System.out.println("  The sync's status                      http://localhost:" + Workspace.SYNC_PORT
                + "/sync/status");
    }

    /** Where the copy stands: not started yet (run ks sync), or running in one direction. */
    private void printCopy() throws Exception {
        JsonNode status = new Api(workspace).syncStatus();
        System.out.println();
        if (StartSync.state(status, "reverse").equals("RUNNING")) {
            System.out.println("  The new application owns the writes; the copy back (MongoDB -> legacy) runs.");
        } else if (StartSync.state(status, "forward").equals("RUNNING")
                || StartSync.state(status, "forward").equals("STARTING")) {
            try (MongoClient client = mongo.client()) {
                System.out.printf("  The sync runs (legacy -> MongoDB): MongoDB holds %,d of the legacy's %,d members.%n",
                        client.getDatabase("kitchensink").getCollection("members").estimatedDocumentCount(),
                        postgres.count());
            }
        } else {
            System.out.printf("  The legacy owns its %,d members; MongoDB is not loaded yet. To copy them, then every"
                    + " change: ks sync%n", postgres.count());
        }
    }

    private Process launch(String name, Path jar, String heap, Map<String, String> env, List<String> args)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.add("-Xmx" + heap);
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(workspace.logs().resolve(name + ".log").toFile()));
        builder.environment().putAll(env);
        builder.environment().put("SERVER_ADDRESS", "127.0.0.1"); // this machine only: no firewall prompt
        Process process = builder.start();
        processes.add(process);
        Leftovers.record(workspace, name, process.toHandle());
        return process;
    }

    private void launchAndWait(String label, String name, Path jar, String heap, Map<String, String> env,
            String health) throws Exception {
        console.step(label);
        Process process = launch(name, jar, heap, env, List.of());
        waitFor(process, name, health);
        console.ok("port " + env.get("PORT"));
    }

    private void waitFor(Process process, String name, String url) throws Exception {
        Api api = new Api(workspace);
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3);
        while (!api.healthy(url)) {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " stopped: see .local/logs/" + name + ".log");
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(name + " did not start in 3 minutes: see .local/logs/" + name
                        + ".log");
            }
            Thread.sleep(500);
        }
    }

    private void checkJars() {
        Map<String, Path> jars = new LinkedHashMap<>();
        jars.put("legacy", workspace.jar("legacy", "kitchensink-legacy.jar"));
        jars.put("app", workspace.jar("kitchensink-app", "kitchensink-app-exec.jar"));
        jars.put("sync", workspace.jar("kitchensink-sync", "kitchensink-sync-exec.jar"));
        jars.put("facade", workspace.jar("kitchensink-facade", "kitchensink-facade-exec.jar"));
        for (var jar : jars.entrySet()) {
            if (!Files.exists(jar.getValue())) {
                throw new IllegalStateException("The " + jar.getKey() + " is not built (" + jar.getValue()
                        + "). Run: mvnw package -DskipTests");
            }
        }
    }

    private static void checkPortsFree(int waitSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(waitSeconds);
        for (int port : new int[] {Workspace.POSTGRES_PORT, Workspace.MONGO_PORT, Workspace.LEGACY_PORT,
                Workspace.APP_PORT, Workspace.SYNC_PORT, Workspace.FACADE_PORT}) {
            while (!free(port)) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("Port " + port + " is already in use by another program. Stop "
                            + "it, then try again.");
                }
                Thread.sleep(500);
            }
        }
    }

    private static boolean free(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void stop() {
        stopping = true;
        System.out.println();
        System.out.println("Stopping...");
        for (Process process : processes.reversed()) {
            process.destroy();
        }
        for (Process process : processes) {
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
            }
        }
        if (mongo != null) {
            mongo.close();
        }
        if (postgres != null) {
            try {
                postgres.close();
            } catch (IOException e) {
                System.out.println("PostgreSQL: " + e.getMessage());
            }
        }
        Leftovers.forget(workspace);
        System.out.println("Stopped.");
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
