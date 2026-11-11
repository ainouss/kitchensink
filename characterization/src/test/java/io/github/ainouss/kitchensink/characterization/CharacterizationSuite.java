package io.github.ainouss.kitchensink.characterization;

import java.net.URI;
import java.nio.file.Path;
import java.util.stream.Stream;

import io.github.ainouss.kitchensink.local.CaseRunner;
import io.github.ainouss.kitchensink.local.Cases;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * One test per case in {@code characterization/cases/*.json}, played against {@link #target()}. Subclasses choose
 * the target.
 */
abstract class CharacterizationSuite {

    /** The case files, relative to this module (Maven runs the tests from there). */
    static final Path CASES = Path.of("cases");

    /** The base URL the case paths are relative to. */
    abstract URI target();

    @TestFactory
    Stream<DynamicTest> legacyBehaviour() {
        CaseRunner runner = new CaseRunner(target());
        return Cases.load(CASES).stream()
                .map(c -> DynamicTest.dynamicTest(c.displayName(), () -> runner.play(c)));
    }
}
