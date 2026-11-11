package io.github.ainouss.kitchensink.local;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import tools.jackson.databind.JsonNode;

/**
 * {@code ks sync}: starts the copy from the legacy to MongoDB. The sync reads a consistent snapshot of the legacy's
 * members (the initial load), then every change committed after it, from PostgreSQL's log: no gap between the two.
 * It is a step of its own because, in production, it is planned: it reads the whole table, and PostgreSQL keeps its
 * log for the copy from then on. Once started, it restarts with the sync until the cutover stops it.
 */
final class StartSync {

    private final Api api;
    private final Console console = new Console();

    StartSync(Workspace workspace) throws Exception {
        this.api = new Api(workspace);
    }

    boolean run() throws Exception {
        JsonNode status = api.syncStatus();
        if (state(status, "reverse").equals("RUNNING") || api.control().path("writes").asString().equals("NEW")) {
            System.out.println("The new application owns the writes: the copy runs from MongoDB to the legacy."
                    + " To give the writes back, run: ks rollback");
            return false;
        }
        console.title("The initial load, then every change: legacy -> MongoDB");
        console.step("sync started (snapshot, then change data capture)");
        if (state(status, "forward").equals("RUNNING")) {
            console.ok("already running");
        } else {
            api.post(api.sync + "/sync/forward/start");
            console.ok();
        }
        return follow();
    }

    /** Shows the initial load until MongoDB holds as many members as the legacy. */
    private boolean follow() throws Exception {
        long legacy = legacyCount();
        long started = System.currentTimeMillis();
        try (MongoClient client = MongoClients.create("mongodb://127.0.0.1:" + Workspace.MONGO_PORT
                + "/?directConnection=true")) {
            var members = client.getDatabase("kitchensink").getCollection("members");
            long copied = members.estimatedDocumentCount();
            if (copied >= legacy) {
                System.out.printf("%n  MongoDB holds %,d members: up to date. The sync streams every change.%n", copied);
                return true;
            }
            while (copied < legacy) {
                String forward = state(api.syncStatus(), "forward");
                if (forward.equals("FAILED") || forward.equals("STOPPED")) {
                    System.out.println();
                    System.out.println("  The sync stopped: " + api.syncStatus().path("forward").path("failure")
                            .asString() + ". See .local/logs/sync.log");
                    return false;
                }
                System.out.printf("\r  initial load: %,d of %,d members", copied, legacy);
                System.out.flush();
                Thread.sleep(1_000);
                copied = members.estimatedDocumentCount();
            }
            System.out.printf("\r  copied %,d members in %.1f s. The sync now streams every change.            %n",
                    copied, (System.currentTimeMillis() - started) / 1000.0);
            return true;
        }
    }

    private static long legacyCount() throws SQLException {
        String url = "jdbc:postgresql://localhost:" + Workspace.POSTGRES_PORT + "/" + LegacyDatabase.DATABASE;
        try (Connection connection = DriverManager.getConnection(url, "postgres", "postgres");
             ResultSet rs = connection.createStatement().executeQuery("SELECT count(*) FROM member")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static String state(JsonNode syncStatus, String direction) {
        return syncStatus.path(direction).path("state").asString();
    }
}
