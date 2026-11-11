package io.github.ainouss.kitchensink.local;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;

/**
 * The processes {@code ks start} runs, written to {@code .local/pids} as they start.
 * When a run ends badly (its window closed, the process killed), they keep running: they hold the ports and lock
 * the data. The next {@code ks start}, or {@code ks stop}, stops them.
 * Each line is {@code <name> <pid> <start time>}: the start time makes sure a reused PID is never stopped.
 */
final class Leftovers {

    private static final List<String> SERVICES = List.of("legacy", "app", "sync", "facade");

    private Leftovers() {
    }

    record Entry(String name, long pid, long started) {

        /** The process, if it is still the one that was started. */
        Optional<ProcessHandle> running() {
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).filter(process -> {
                ProcessHandle.Info info = process.info();
                boolean sameProgram = info.command().map(c -> c.toLowerCase().contains(program())).orElse(false);
                boolean sameStart = started == 0
                        || info.startInstant().map(s -> Math.abs(s.toEpochMilli() - started) < 1_000).orElse(true);
                return sameProgram && sameStart;
            });
        }

        String program() {
            return name.equals("mongod") || name.equals("postgres") ? name : "java";
        }

        @Override
        public String toString() {
            return name + " (pid " + pid + ")";
        }
    }

    /** Adds a process this run started. Written at once, so a run that dies still leaves the list. */
    static synchronized void record(Workspace workspace, String name, ProcessHandle process) throws IOException {
        long started = process.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
        Files.createDirectories(workspace.local());
        Files.writeString(file(workspace), name + " " + process.pid() + " " + started + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** After a clean stop: nothing is left. */
    static void forget(Workspace workspace) {
        try {
            Files.deleteIfExists(file(workspace));
        } catch (IOException ignored) {
            // the next start checks each process anyway
        }
    }

    /** Refuses a second {@code ks start} while one is running. */
    static void refuseIfRunning(Workspace workspace) throws IOException {
        for (Entry entry : read(workspace)) {
            if (entry.name().equals("ks") && entry.pid() != ProcessHandle.current().pid()
                    && entry.running().isPresent()) {
                throw new IllegalStateException("\"ks start\" is running (pid " + entry.pid()
                        + "). Stop it with Ctrl+C in its window, or run: ks stop");
            }
        }
    }

    /**
     * Stops everything a run started: {@code ks start} itself, the four services, MongoDB, PostgreSQL.
     * Returns what was stopped.
     */
    static List<String> stop(Workspace workspace) throws IOException {
        List<Entry> entries = new ArrayList<>(read(workspace));
        // Runs older than this file: find the databases from their own data directories.
        if (entries.stream().noneMatch(e -> e.name().equals("postgres"))) {
            postgresOn(workspace.postgresData()).ifPresent(entries::add);
        }
        if (entries.stream().noneMatch(e -> e.name().equals("mongod"))) {
            mongodOn(workspace.mongoData()).ifPresent(entries::add);
        }
        List<String> stopped = new ArrayList<>();
        stop(entries, List.of("ks"), Duration.ofSeconds(60), stopped); // on macOS and Linux it stops the rest itself
        stop(entries, SERVICES, Duration.ofSeconds(20), stopped);
        stop(entries, List.of("mongod"), Duration.ofSeconds(20), stopped);
        stop(entries, List.of("postgres"), Duration.ofSeconds(20), stopped);
        forget(workspace);
        return stopped;
    }

    private static void stop(List<Entry> entries, List<String> names, Duration patience, List<String> stopped) {
        List<ProcessHandle> stopping = new ArrayList<>();
        List<ProcessHandle> children = new ArrayList<>();
        for (Entry entry : entries) {
            if (names.contains(entry.name())) {
                entry.running().ifPresent(process -> {
                    if (!entry.name().equals("ks")) {
                        // On Windows, PostgreSQL's processes outlive a killed server, and hold its port.
                        children.addAll(process.descendants().toList());
                    }
                    process.destroy();
                    stopping.add(process);
                    stopped.add(entry.toString());
                });
            }
        }
        awaitExit(stopping, patience);
        List<ProcessHandle> remaining = children.stream().filter(ProcessHandle::isAlive).toList();
        remaining.forEach(ProcessHandle::destroy);
        awaitExit(remaining, Duration.ofSeconds(10));
    }

    private static void awaitExit(List<ProcessHandle> processes, Duration patience) {
        for (ProcessHandle process : processes) {
            try {
                process.onExit().get(patience.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception notYet) {
                process.destroyForcibly();
                try {
                    process.onExit().get(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // the delete or the port check that follows says what still runs
                }
            }
        }
    }

    /** Deletes a directory. On Windows a file can stay locked a moment after its process stopped: retry. */
    static void delete(Path path) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                Stack.deleteRecursively(path);
                return;
            } catch (IOException e) {
                if (attempt == 20) {
                    throw new IllegalStateException("Cannot delete " + path + " (" + e.getMessage() + "). Another "
                            + "program still uses it: run ks stop, or close that program, then try again.");
                }
                Thread.sleep(500);
            }
        }
    }

    private static List<Entry> read(Workspace workspace) throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (!Files.exists(file(workspace))) {
            return entries;
        }
        for (String line : Files.readAllLines(file(workspace))) {
            String[] fields = line.strip().split(" ");
            if (fields.length == 3) {
                try {
                    entries.add(new Entry(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2])));
                } catch (NumberFormatException ignored) {
                    // a line cut short: skip it
                }
            }
        }
        return entries;
    }

    /** PostgreSQL writes its PID on the first line of postmaster.pid in its data directory. */
    private static Optional<Entry> postgresOn(Path data) {
        try {
            Path pidFile = data.resolve("postmaster.pid");
            if (!Files.exists(pidFile)) {
                return Optional.empty();
            }
            long pid = Long.parseLong(Files.readAllLines(pidFile).getFirst().strip());
            return Optional.of(new Entry("postgres", pid, 0));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * MongoDB locks mongod.lock on Windows, so its PID is asked to MongoDB itself, on the port {@code ks start} uses,
     * and only if that MongoDB runs on this data directory.
     */
    private static Optional<Entry> mongodOn(Path data) {
        if (!Files.exists(data.resolve("mongod.lock")) || !listening(Workspace.MONGO_PORT)) {
            return Optional.empty();
        }
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://127.0.0.1:" + Workspace.MONGO_PORT
                        + "/?directConnection=true"))
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(2, TimeUnit.SECONDS))
                .applyToSocketSettings(socket -> socket.connectTimeout(2, TimeUnit.SECONDS))
                .build();
        try (MongoClient client = MongoClients.create(settings)) {
            var admin = client.getDatabase("admin");
            String dbPath = admin.runCommand(new Document("getCmdLineOpts", 1))
                    .get("parsed", Document.class).get("storage", Document.class).getString("dbPath");
            if (!Files.isSameFile(Path.of(dbPath), data)) {
                return Optional.empty();
            }
            long pid = ((Number) admin.runCommand(new Document("serverStatus", 1)).get("pid")).longValue();
            return Optional.of(new Entry("mongod", pid, 0));
        } catch (Exception nothingThere) {
            return Optional.empty();
        }
    }

    private static boolean listening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path file(Workspace workspace) {
        return workspace.local().resolve("pids");
    }
}
