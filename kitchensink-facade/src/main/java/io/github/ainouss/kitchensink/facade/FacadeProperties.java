package io.github.ainouss.kitchensink.facade;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * The facade's settings ({@code kitchensink.facade.*}): the two upstream URLs, the admin token and the shadow limits.
 */
@ConfigurationProperties("kitchensink.facade")
public record FacadeProperties(URI legacyUrl, URI newUrl, String adminToken, Duration controlRefresh,
                               Duration upstreamTimeout, Shadow shadow) {

    public record Shadow(Duration timeout, DataSize maxBody, int maxStored) {
    }
}
