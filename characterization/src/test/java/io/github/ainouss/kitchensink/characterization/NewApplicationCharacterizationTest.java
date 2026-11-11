package io.github.ainouss.kitchensink.characterization;

import java.net.URI;

import io.github.ainouss.kitchensink.KitchensinkApplication;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Runs the cases against the new application, as it ships, on a random port with a real MongoDB. Skipped when
 * {@code kitchensink.target} is set.
 */
@SpringBootTest(classes = KitchensinkApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMongo.class)
@DisabledIfSystemProperty(named = TargetCharacterizationTest.TARGET, matches = ".+",
        disabledReason = "the cases are played against kitchensink.target instead")
class NewApplicationCharacterizationTest extends CharacterizationSuite {

    @Value("${local.server.port}")
    int port;

    @Override
    URI target() {
        return URI.create("http://localhost:" + port);
    }
}
