package io.github.ainouss.kitchensink.local;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * {@code ks rules}: plays the legacy's recorded behaviour ({@code characterization/cases/}) against the
 * running system, the way a client would. Through the facade by default, so it tests what users get, whichever
 * application answers. An application on its own only while it owns the writes: the cases register members, and a
 * registration that bypasses the owner would bypass the copy.
 */
final class Rules {

    private final Workspace workspace;
    private final Api api;

    Rules(Workspace workspace) throws Exception {
        this.workspace = workspace;
        this.api = new Api(workspace);
    }

    boolean run(String target) throws Exception {
        String writes = api.control().path("writes").asString();
        if (writes.equals("PAUSED")) {
            System.out.println("Writes are paused: a switch is under way. Run it again once it is done.");
            return false;
        }
        String owner = writes.equals("NEW") ? "the new application" : "the legacy";
        String base;
        String answers;
        switch (target) {
            case "facade" -> {
                base = "http://localhost:" + Workspace.FACADE_PORT + "/kitchensink";
                answers = "through the facade: " + owner + " answers";
            }
            case "legacy" -> {
                if (!writes.equals("LEGACY")) {
                    return refuse("the new application");
                }
                base = "http://localhost:" + Workspace.LEGACY_PORT + "/kitchensink";
                answers = "the legacy";
            }
            case "new" -> {
                if (!writes.equals("NEW")) {
                    return refuse("the legacy");
                }
                base = "http://localhost:" + Workspace.APP_PORT + "/kitchensink";
                answers = "the new application";
            }
            default -> {
                System.out.println("Unknown target " + target + ": ks rules [legacy|new]");
                return false;
            }
        }

        List<Cases.Case> cases = Cases.load(workspace.root.resolve("characterization").resolve("cases"));
        CaseRunner runner = new CaseRunner(URI.create(base));
        System.out.printf("%n== The legacy's rules, recorded as %d cases, played against %s (%s)%n", cases.size(),
                base, answers);
        int passed = 0;
        for (Cases.Case c : cases) {
            Optional<String> difference = runner.check(c);
            if (difference.isEmpty()) {
                passed++;
                System.out.printf("  ok    %-8s %s%n", c.rule(), c.title());
            } else {
                System.out.printf("  FAIL  %-8s %s%n        %s%n", c.rule(), c.title(),
                        difference.get().replace("\n", "\n        "));
            }
        }
        System.out.printf("%n  %d of %d cases %s.%n", passed, cases.size(),
                passed == cases.size() ? "answered as the legacy did" : "answered as the legacy did: see FAIL above");
        return passed == cases.size();
    }

    private static boolean refuse(String owner) {
        System.out.println(owner.substring(0, 1).toUpperCase() + owner.substring(1) + " owns the writes, and the"
                + " cases register members: on another application they would bypass the copy. Run them through"
                + " the facade: ks rules");
        return false;
    }
}
