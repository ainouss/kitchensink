package io.github.ainouss.kitchensink.sync;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Shares the recorded sync direction between the streams and the status API. */
@Configuration(proxyBeanMethods = false)
class SyncDirectionConfiguration {

    @Bean
    SyncDirection syncDirection(MongoTemplate mongo, SyncProperties properties) {
        return new SyncDirection(mongo.getDb(), properties);
    }
}
