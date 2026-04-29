package org.jclaude.runtime.git;

import java.util.Optional;

/** Outcome record matching the Rust {@code GreenContractOutcome} tagged enum. */
public sealed interface GreenContractOutcome {

    boolean is_satisfied();

    GreenLevel required_level();

    record Satisfied(GreenLevel required_level, GreenLevel observed_level) implements GreenContractOutcome {
        @Override
        public boolean is_satisfied() {
            return true;
        }
    }

    record Unsatisfied(GreenLevel required_level, Optional<GreenLevel> observed_level) implements GreenContractOutcome {
        @Override
        public boolean is_satisfied() {
            return false;
        }
    }
}
