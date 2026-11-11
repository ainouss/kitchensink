package io.github.ainouss.kitchensink.local;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import de.flapdoodle.commons.reverse.TransitionWalker;
import de.flapdoodle.commons.reverse.transitions.Start;
import de.flapdoodle.embed.mongo.commands.MongodArguments;
import de.flapdoodle.embed.mongo.config.Net;
import de.flapdoodle.embed.mongo.config.Storage;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.ImmutableMongod;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.embed.mongo.types.DatabaseDir;
import de.flapdoodle.embed.process.io.ProcessOutput;
import org.bson.Document;

/**
 * MongoDB 8.0, downloaded once and started as a single-node replica set
 * (change streams and transactions need a replica set).
 */
public final class LocalMongo implements AutoCloseable {

    private static final String REPLICA_SET = "rs0";

    private final TransitionWalker.ReachedState<RunningMongodProcess> process;
    private final int port;

    private LocalMongo(TransitionWalker.ReachedState<RunningMongodProcess> process, int port) {
        this.process = process;
        this.port = port;
    }

    /** Starts MongoDB on {@code port}. With a {@code dataDirectory} the data is kept; without one it is temporary. */
    public static LocalMongo start(int port, Path dataDirectory) throws IOException, InterruptedException {
        MongodArguments arguments = MongodArguments.defaults()
                .withReplication(Storage.of(REPLICA_SET, 0))
                .withArgs(java.util.Map.of("--wiredTigerCacheSizeGB", "1"));
        ImmutableMongod mongod = Mongod.instance()
                .withNet(Start.to(Net.class).initializedWith(Net.of("127.0.0.1", port, false)))
                .withMongodArguments(Start.to(MongodArguments.class).initializedWith(arguments))
                .withProcessOutput(Start.to(ProcessOutput.class).initializedWith(ProcessOutput.silent()));
        if (dataDirectory != null) {
            Files.createDirectories(dataDirectory);
            mongod = mongod.withDatabaseDir(Start.to(DatabaseDir.class).initializedWith(DatabaseDir.of(dataDirectory)));
        }
        LocalMongo mongo = new LocalMongo(mongod.start(Version.Main.V8_0), port);
        mongo.initiateReplicaSet();
        return mongo;
    }

    /** A free TCP port, for tests. */
    public static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    public int port() {
        return port;
    }

    public String uri(String database) {
        return "mongodb://127.0.0.1:" + port + "/" + database + "?directConnection=true";
    }

    public MongoClient client() {
        return MongoClients.create(uri(""));
    }

    /** The mongod process, as MongoDB reports it. */
    public ProcessHandle process() {
        try (MongoClient client = client()) {
            long pid = ((Number) client.getDatabase("admin").runCommand(new Document("serverStatus", 1)).get("pid"))
                    .longValue();
            return ProcessHandle.of(pid).orElseThrow(() -> new IllegalStateException("MongoDB is not running"));
        }
    }

    private void initiateReplicaSet() throws InterruptedException {
        try (MongoClient client = client()) {
            MongoDatabase admin = client.getDatabase("admin");
            try {
                admin.runCommand(new Document("replSetGetStatus", 1));
            } catch (MongoCommandException notInitiated) {
                admin.runCommand(new Document("replSetInitiate", new Document("_id", REPLICA_SET)
                        .append("members", List.of(new Document("_id", 0).append("host", "127.0.0.1:" + port)))));
            }
            for (int attempt = 0; attempt < 120; attempt++) {
                if (admin.runCommand(new Document("hello", 1)).getBoolean("isWritablePrimary", false)) {
                    return;
                }
                Thread.sleep(250);
            }
            throw new IllegalStateException("MongoDB did not become primary");
        }
    }

    @Override
    public void close() {
        process.close();
    }
}
