package io.github.ainouss.kitchensink;

import com.mongodb.ConnectionString;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import io.github.ainouss.kitchensink.local.LocalMongo;

/** A real MongoDB for the tests, started once per run from the {@code local} module. */
@TestConfiguration(proxyBeanMethods = false)
public class TestMongo {

    static final LocalMongo MONGO = start();

    private static LocalMongo start() {
        try {
            LocalMongo mongo = LocalMongo.start(LocalMongo.freePort(), null);
            Runtime.getRuntime().addShutdownHook(new Thread(mongo::close));
            return mongo;
        } catch (Exception e) {
            throw new IllegalStateException("MongoDB did not start for the tests", e);
        }
    }

    @Bean
    MongoConnectionDetails mongoConnectionDetails() {
        return () -> new ConnectionString(MONGO.uri("test"));
    }
}
