package io.github.ainouss.kitchensink.characterization;

import java.net.URI;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Runs the cases against a running system given by {@code -Dkitchensink.target=<base URL>}, such as the legacy or the
 * facade. Safe to run again on the same system: each run registers new emails.
 */
@EnabledIfSystemProperty(named = TargetCharacterizationTest.TARGET, matches = ".+",
        disabledReason = "no -Dkitchensink.target=<base URL> given")
class TargetCharacterizationTest extends CharacterizationSuite {

    static final String TARGET = "kitchensink.target";

    @Override
    URI target() {
        return URI.create(System.getProperty(TARGET));
    }
}
