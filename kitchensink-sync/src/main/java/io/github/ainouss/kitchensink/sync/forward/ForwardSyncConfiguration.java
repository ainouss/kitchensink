package io.github.ainouss.kitchensink.sync.forward;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import io.github.ainouss.kitchensink.sync.SyncProperties;
import io.github.ainouss.kitchensink.sync.member.MemberRowMapper;

/** The forward stream's beans. The stream always exists, so the API can start and stop it. */
@Configuration(proxyBeanMethods = false)
class ForwardSyncConfiguration {

    @Bean
    ForwardSyncStatus forwardSyncStatus() {
        return new ForwardSyncStatus();
    }

    @Bean
    ForwardSync forwardSync(SyncProperties properties, MongoTemplate mongo, MemberRowMapper mapper,
                            ForwardSyncStatus status) {
        return new ForwardSync(properties, mongo.getDb(), mapper, status);
    }
}
