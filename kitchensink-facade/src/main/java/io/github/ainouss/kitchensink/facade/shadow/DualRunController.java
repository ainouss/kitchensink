package io.github.ainouss.kitchensink.facade.shadow;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /facade/shadow}: this instance's dual-run counts, totals and per route. */
@RestController
class DualRunController {

    private final DualRun dualRun;

    DualRunController(DualRun dualRun) {
        this.dualRun = dualRun;
    }

    @GetMapping("/facade/shadow")
    Map<String, Object> stats() {
        return dualRun.stats();
    }
}
