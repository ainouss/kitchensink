package io.github.ainouss.kitchensink.sync;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The worker's settings ({@code kitchensink.sync.*}). See {@code application.yaml} for the environment variables.
 */
@ConfigurationProperties("kitchensink.sync")
public record SyncProperties(Legacy legacy, Forward forward, Reconcile reconcile, Reverse reverse) {

    /**
     * The legacy PostgreSQL database, reached with the worker's own account.
     *
     * @param jdbcUrl {@code jdbc:postgresql://host[:port]/database}
     */
    public record Legacy(String jdbcUrl, String user, String password) {

        public String host() {
            return uri().getHost();
        }

        public int port() {
            int port = uri().getPort();
            return port == -1 ? 5432 : port;
        }

        public String database() {
            String path = uri().getPath();
            if (path == null || path.length() < 2) {
                throw new IllegalStateException("LEGACY_JDBC_URL names no database: " + jdbcUrl);
            }
            return path.substring(1);
        }

        private URI uri() {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
                throw new IllegalStateException("LEGACY_JDBC_URL must be jdbc:postgresql://host[:port]/database, not "
                        + jdbcUrl);
            }
            return URI.create(jdbcUrl.substring("jdbc:".length()));
        }
    }

    /**
     * The forward stream: PostgreSQL to MongoDB.
     *
     * @param enabled       whether the stream starts with the worker (only until a direction is recorded)
     * @param slot          the replication slot, kept across restarts
     * @param publication   the PostgreSQL publication to read from
     * @param batchSize     the largest batch Debezium hands over, and so the largest bulk write
     * @param heartbeat     how often the slot moves on when the table is quiet, so PostgreSQL can free its log
     * @param probeInterval how often a probe is written to measure the end-to-end lag
     */
    public record Forward(boolean enabled, String slot, String publication, int batchSize, Duration heartbeat,
                          Duration probeInterval) {
    }

    /**
     * The reconciliation, which compares the two databases.
     *
     * @param scheduled  whether it runs every {@code interval} (it can always run on demand)
     * @param interval   time between two scheduled runs
     * @param settle     how long to wait before re-checking a difference, to let the stream catch up
     * @param recheckMax the most differences re-checked; above this the run fails anyway
     * @param samples    how many examples of each kind of difference the report shows
     */
    public record Reconcile(boolean scheduled, Duration interval, Duration settle, int recheckMax, int samples) {
    }

    /**
     * The reverse stream: MongoDB changes back into PostgreSQL after the cutover, so a rollback loses nothing.
     *
     * @param enabled       whether the stream starts with the worker (only until a direction is recorded)
     * @param probeInterval how often a probe is written to measure the end-to-end lag
     */
    public record Reverse(boolean enabled, Duration probeInterval) {
    }
}
