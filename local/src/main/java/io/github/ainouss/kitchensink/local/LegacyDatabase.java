package io.github.ainouss.kitchensink.local;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * The legacy's PostgreSQL, run from binaries Maven downloads (no install needed).
 * Logical WAL is on, so the sync can read changes, and names sort with ICU en-US, like production.
 */
public final class LegacyDatabase implements AutoCloseable {

    public static final String DATABASE = "kitchensink";
    public static final String APP_USER = "kitchensink";
    public static final String APP_PASSWORD = "kitchensink";
    public static final String SYNC_USER = "kitchensink_sync";
    public static final String SYNC_PASSWORD = "kitchensink_sync";
    public static final String PUBLICATION = "kitchensink";

    private final EmbeddedPostgres postgres;
    private final Path dataDirectory;
    private final boolean created;

    private LegacyDatabase(EmbeddedPostgres postgres, Path dataDirectory, boolean created) {
        this.postgres = postgres;
        this.dataDirectory = dataDirectory;
        this.created = created;
    }

    /**
     * Starts PostgreSQL on {@code port}. With a {@code dataDirectory}, the data is kept between runs;
     * without one, a fresh temporary database is used (tests).
     */
    public static LegacyDatabase start(int port, Path dataDirectory, Path log) throws IOException, SQLException {
        EmbeddedPostgres.Builder builder = EmbeddedPostgres.builder()
                .setPort(port)
                .setPGStartupWait(Duration.ofSeconds(60))
                .setLocaleConfig("locale-provider", "icu")
                .setLocaleConfig("icu-locale", "en-US")
                .setLocaleConfig("encoding", "UTF8")
                .setServerConfig("wal_level", "logical")
                .setServerConfig("max_replication_slots", "10")
                .setServerConfig("max_wal_senders", "10")
                .setServerConfig("max_wal_size", "2GB");
        Path data = dataDirectory;
        if (data != null) {
            Files.createDirectories(data);
            builder.setDataDirectory(data).setCleanDataDirectory(false);
        } else {
            // A new directory each time, removed when the database stops.
            data = Path.of(System.getProperty("java.io.tmpdir"), "kitchensink-pg-" + java.util.UUID.randomUUID());
            builder.setDataDirectory(data).setCleanDataDirectory(true).setServerConfig("fsync", "off");
        }
        if (log != null) {
            Files.createDirectories(log.getParent());
            builder.setOutputRedirector(Redirect.appendTo(log.toFile()))
                    .setErrorRedirector(Redirect.appendTo(log.toFile()));
        }
        EmbeddedPostgres postgres = builder.start();
        boolean created = false;
        try (Connection admin = postgres.getPostgresDatabase().getConnection();
             Statement statement = admin.createStatement();
             ResultSet rs = statement.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + DATABASE + "'")) {
            if (!rs.next()) {
                statement.execute("CREATE DATABASE " + DATABASE);
                created = true;
            }
        }
        LegacyDatabase database = new LegacyDatabase(postgres, data, created);
        if (created) {
            database.runScript("legacy-db/schema.sql", "");
        }
        return database;
    }

    /** True when this start created the database (first run). */
    public boolean created() {
        return created;
    }

    public int port() {
        return postgres.getPort();
    }

    /** The PostgreSQL server process: its PID is the first line of postmaster.pid. */
    public ProcessHandle process() throws IOException {
        long pid = Long.parseLong(Files.readAllLines(dataDirectory.resolve("postmaster.pid")).getFirst().strip());
        return ProcessHandle.of(pid).orElseThrow(() -> new IllegalStateException("PostgreSQL is not running"));
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://localhost:" + port() + "/" + DATABASE;
    }

    /** A connection as the database owner (what a DBA uses). */
    public Connection owner() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), "postgres", "postgres");
    }

    /** Adds {@code rows} generated members after member 0. */
    public void seed(long rows) throws SQLException {
        runScript("legacy-db/seed.sql", Long.toString(rows));
    }

    public long count() throws SQLException {
        try (Connection connection = owner(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM member")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public void execute(String... statements) throws SQLException {
        try (Connection connection = owner(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private void runScript(String resource, String rows) throws SQLException {
        String script = read(resource).replace("ROWS", rows);
        try (Connection connection = owner(); Statement statement = connection.createStatement()) {
            for (String sql : statements(script)) {
                statement.execute(sql);
            }
        }
    }

    /** Splits a script on the semicolons that end a line, but not inside a $$ ... $$ block. */
    static List<String> statements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inBlock = false;
        for (String line : script.split("\n")) {
            String trimmed = line.strip();
            if (!inBlock && (trimmed.isEmpty() || trimmed.startsWith("--"))) {
                continue;
            }
            current.append(line).append('\n');
            int dollars = line.split("\\$\\$", -1).length - 1;
            if (dollars % 2 == 1) {
                inBlock = !inBlock;
            }
            if (!inBlock && trimmed.endsWith(";")) {
                statements.add(current.toString().strip());
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString().strip());
        }
        return statements;
    }

    private static String read(String resource) {
        try (InputStream in = LegacyDatabase.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        postgres.close();
    }
}
