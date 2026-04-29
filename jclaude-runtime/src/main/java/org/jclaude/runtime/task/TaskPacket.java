package org.jclaude.runtime.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/** Typed task packet describing a unit of work for a sub-agent. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskPacket(
        @JsonProperty("objective") String objective,
        @JsonProperty("scope") TaskScope scope,
        @JsonProperty("scope_path") String scope_path,
        @JsonProperty("repo") String repo,
        @JsonProperty("worktree") String worktree,
        @JsonProperty("branch_policy") String branch_policy,
        @JsonProperty("acceptance_tests") List<String> acceptance_tests,
        @JsonProperty("commit_policy") String commit_policy,
        @JsonProperty("reporting_contract") String reporting_contract,
        @JsonProperty("escalation_policy") String escalation_policy) {

    @JsonCreator
    public TaskPacket {
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(repo, "repo");
        Objects.requireNonNull(branch_policy, "branch_policy");
        Objects.requireNonNull(acceptance_tests, "acceptance_tests");
        Objects.requireNonNull(commit_policy, "commit_policy");
        Objects.requireNonNull(reporting_contract, "reporting_contract");
        Objects.requireNonNull(escalation_policy, "escalation_policy");
        acceptance_tests = List.copyOf(acceptance_tests);
    }
}
