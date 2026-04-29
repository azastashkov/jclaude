package org.jclaude.runtime.git;

import java.util.Optional;

/** Green contract record mirroring the Rust definition. */
public record GreenContract(GreenLevel required_level) {

    public GreenContractOutcome evaluate(Optional<GreenLevel> observed_level) {
        if (observed_level.isPresent() && observed_level.get().ordinal() >= required_level.ordinal()) {
            return new GreenContractOutcome.Satisfied(required_level, observed_level.get());
        }
        return new GreenContractOutcome.Unsatisfied(required_level, observed_level);
    }

    public boolean is_satisfied_by(GreenLevel observed_level) {
        return observed_level.ordinal() >= required_level.ordinal();
    }
}
