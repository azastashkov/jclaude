package org.jclaude.runtime.recovery;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/** Individual recovery step that may be executed as part of a {@link RecoveryRecipe}. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RecoveryStep.AcceptTrustPrompt.class, name = "accept_trust_prompt"),
    @JsonSubTypes.Type(value = RecoveryStep.RedirectPromptToAgent.class, name = "redirect_prompt_to_agent"),
    @JsonSubTypes.Type(value = RecoveryStep.RebaseBranch.class, name = "rebase_branch"),
    @JsonSubTypes.Type(value = RecoveryStep.CleanBuild.class, name = "clean_build"),
    @JsonSubTypes.Type(value = RecoveryStep.RetryMcpHandshake.class, name = "retry_mcp_handshake"),
    @JsonSubTypes.Type(value = RecoveryStep.RestartPlugin.class, name = "restart_plugin"),
    @JsonSubTypes.Type(value = RecoveryStep.RestartWorker.class, name = "restart_worker"),
    @JsonSubTypes.Type(value = RecoveryStep.EscalateToHuman.class, name = "escalate_to_human")
})
public sealed interface RecoveryStep {

    @JsonTypeName("accept_trust_prompt")
    record AcceptTrustPrompt() implements RecoveryStep {}

    @JsonTypeName("redirect_prompt_to_agent")
    record RedirectPromptToAgent() implements RecoveryStep {}

    @JsonTypeName("rebase_branch")
    record RebaseBranch() implements RecoveryStep {}

    @JsonTypeName("clean_build")
    record CleanBuild() implements RecoveryStep {}

    @JsonTypeName("retry_mcp_handshake")
    record RetryMcpHandshake(@JsonProperty("timeout") long timeout) implements RecoveryStep {}

    @JsonTypeName("restart_plugin")
    record RestartPlugin(@JsonProperty("name") String name) implements RecoveryStep {}

    @JsonTypeName("restart_worker")
    record RestartWorker() implements RecoveryStep {}

    @JsonTypeName("escalate_to_human")
    record EscalateToHuman(@JsonProperty("reason") String reason) implements RecoveryStep {}
}
