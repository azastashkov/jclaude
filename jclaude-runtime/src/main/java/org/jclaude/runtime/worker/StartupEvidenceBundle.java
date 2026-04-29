package org.jclaude.runtime.worker;

import java.util.Optional;

/** Evidence bundle for startup-no-evidence classification. */
public record StartupEvidenceBundle(
        WorkerStatus last_lifecycle_state,
        String pane_command,
        Optional<Long> prompt_sent_at,
        boolean prompt_acceptance_state,
        boolean trust_prompt_detected,
        boolean tool_permission_prompt_detected,
        Optional<Long> tool_permission_prompt_age_seconds,
        Optional<ToolPermissionAllowScope> tool_permission_allow_scope,
        boolean transport_healthy,
        boolean mcp_healthy,
        long elapsed_seconds) {

    public StartupEvidenceBundle {
        prompt_sent_at = prompt_sent_at == null ? Optional.empty() : prompt_sent_at;
        tool_permission_prompt_age_seconds =
                tool_permission_prompt_age_seconds == null ? Optional.empty() : tool_permission_prompt_age_seconds;
        tool_permission_allow_scope =
                tool_permission_allow_scope == null ? Optional.empty() : tool_permission_allow_scope;
    }
}
