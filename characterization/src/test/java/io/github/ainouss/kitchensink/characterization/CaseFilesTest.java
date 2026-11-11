package io.github.ainouss.kitchensink.characterization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.ainouss.kitchensink.local.Cases;
import io.github.ainouss.kitchensink.local.Cases.Case;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks that the case files and the normalisation list are well formed, and that the loader rejects typos. */
class CaseFilesTest {

    @Test
    void everyCaseFileLoadsWithUniqueIdsAndTheRunTokenInPlace() {
        List<Case> cases = Cases.load(CharacterizationSuite.CASES, "tok3n");
        assertThat(cases).hasSizeGreaterThanOrEqualTo(20);
        assertThat(cases).extracting(Case::id).doesNotHaveDuplicates();
        assertThat(cases).flatExtracting(Case::steps)
                .allSatisfy(step -> assertThat(String.valueOf(step.request().body())).doesNotContain("{{"));
        assertThat(cases).filteredOn(c -> c.id().equals("r9-r11-register-then-taken")).singleElement()
                .satisfies(c -> assertThat(c.steps().getFirst().request().body()).contains("jane.tok3n@mailinator.com"));
    }

    @Test
    void theRunTokenIsSafeInAnEmailAndNeverLooksLikeAName() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String token = Cases.runToken();
            assertThat(token).matches("[0-9a-z]{9,}");
            tokens.add(token);
        }
        assertThat(tokens).hasSizeGreaterThan(1);
    }

    @Test
    void anUnknownKeyFailsTheLoadInsteadOfPassingSilently() {
        String typo = """
                {"cases": [{"id": "x", "rule": "R1", "title": "t",
                  "steps": [{"request": {"method": "GET", "path": "/rest/members"}, "expect": {"stauts": 200}}]}]}
                """;
        assertThatThrownBy(() -> Cases.parse("typo.json", typo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown key 'stauts'");
    }

    @Test
    void aStepWithoutExpectationIsRefused() {
        String empty = """
                {"cases": [{"id": "x", "rule": "R1", "title": "t",
                  "steps": [{"request": {"method": "GET", "path": "/rest/members"}, "expect": {}}]}]}
                """;
        assertThatThrownBy(() -> Cases.parse("empty.json", empty)).hasMessageContaining("at least one expectation");
    }

    @Test
    void aRuleMustNameTheAssessmentsRules() {
        String rule = """
                {"cases": [{"id": "x", "rule": "names", "title": "t",
                  "steps": [{"request": {"method": "GET", "path": "/rest/members"}, "expect": {"status": 200}}]}]}
                """;
        assertThatThrownBy(() -> Cases.parse("rule.json", rule)).hasMessageContaining("must name assessment rules");
    }

    @Test
    void everyAcceptedDifferenceSaysWhatBothSystemsDoAndWhy() throws IOException {
        JsonNode normalisation;
        try (InputStream in = getClass().getResourceAsStream("/kitchensink/normalisation.json")) {
            assertThat(in).as("kitchensink/normalisation.json from kitchensink-domain").isNotNull();
            normalisation = JsonMapper.builder().build().readTree(in);
        }
        JsonNode accepted = normalisation.get("acceptedDifferences");
        assertThat(accepted.isArray() && !accepted.isEmpty()).isTrue();
        Set<String> rules = new HashSet<>();
        for (JsonNode difference : accepted) {
            for (String field : List.of("rule", "what", "legacy", "new", "decision")) {
                assertThat(difference.get(field)).as(field + " of " + difference).isNotNull();
                assertThat(difference.get(field).stringValue()).as(field + " of " + difference).isNotBlank();
            }
            assertThat(difference.get("rule").stringValue()).matches("R\\d+");
            assertThat(rules.add(difference.get("rule").stringValue())).as("one entry per rule").isTrue();
        }
    }
}
