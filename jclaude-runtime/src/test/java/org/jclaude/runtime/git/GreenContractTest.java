package org.jclaude.runtime.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class GreenContractTest {

    @Test
    void given_matching_level_when_evaluating_contract_then_it_is_satisfied() {
        GreenContract contract = new GreenContract(GreenLevel.PACKAGE);

        GreenContractOutcome outcome = contract.evaluate(Optional.of(GreenLevel.PACKAGE));

        assertThat(outcome).isEqualTo(new GreenContractOutcome.Satisfied(GreenLevel.PACKAGE, GreenLevel.PACKAGE));
        assertThat(outcome.is_satisfied()).isTrue();
    }

    @Test
    void given_higher_level_when_checking_requirement_then_it_still_satisfies_contract() {
        GreenContract contract = new GreenContract(GreenLevel.TARGETED_TESTS);

        boolean is_satisfied = contract.is_satisfied_by(GreenLevel.WORKSPACE);

        assertThat(is_satisfied).isTrue();
    }

    @Test
    void given_lower_level_when_evaluating_contract_then_it_is_unsatisfied() {
        GreenContract contract = new GreenContract(GreenLevel.WORKSPACE);

        GreenContractOutcome outcome = contract.evaluate(Optional.of(GreenLevel.PACKAGE));

        assertThat(outcome)
                .isEqualTo(new GreenContractOutcome.Unsatisfied(GreenLevel.WORKSPACE, Optional.of(GreenLevel.PACKAGE)));
        assertThat(outcome.is_satisfied()).isFalse();
    }

    @Test
    void given_no_green_level_when_evaluating_contract_then_contract_is_unsatisfied() {
        GreenContract contract = new GreenContract(GreenLevel.MERGE_READY);

        GreenContractOutcome outcome = contract.evaluate(Optional.empty());

        assertThat(outcome).isEqualTo(new GreenContractOutcome.Unsatisfied(GreenLevel.MERGE_READY, Optional.empty()));
    }
}
