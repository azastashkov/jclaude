package org.jclaude.cli.parity;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** One row in the canonical 12-scenario list driving the harness. */
public record ScenarioCase(
        String name,
        String permission_mode,
        String allowed_tools,
        String stdin,
        Consumer<HarnessWorkspace> prepare,
        BiConsumer<HarnessWorkspace, ScenarioRun> assertion) {

    @Override
    public String toString() {
        return name;
    }
}
