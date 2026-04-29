package org.jclaude.runtime.worker;

import java.util.Optional;

/** Event payload with structured data. */
public sealed interface WorkerEventPayload {

    record TrustPrompt(String cwd, Optional<WorkerTrustResolution> resolution) implements WorkerEventPayload {
        public TrustPrompt {
            resolution = resolution == null ? Optional.empty() : resolution;
        }
    }

    record ToolPermissionPrompt(
            Optional<String> server_name,
            Optional<String> tool_name,
            long prompt_age_seconds,
            ToolPermissionAllowScope allow_scope,
            String prompt_preview)
            implements WorkerEventPayload {
        public ToolPermissionPrompt {
            server_name = server_name == null ? Optional.empty() : server_name;
            tool_name = tool_name == null ? Optional.empty() : tool_name;
        }
    }

    record PromptDelivery(
            String prompt_preview,
            WorkerPromptTarget observed_target,
            Optional<String> observed_cwd,
            Optional<String> observed_prompt_preview,
            Optional<WorkerTaskReceipt> task_receipt,
            boolean recovery_armed)
            implements WorkerEventPayload {
        public PromptDelivery {
            observed_cwd = observed_cwd == null ? Optional.empty() : observed_cwd;
            observed_prompt_preview = observed_prompt_preview == null ? Optional.empty() : observed_prompt_preview;
            task_receipt = task_receipt == null ? Optional.empty() : task_receipt;
        }
    }

    record StartupNoEvidence(StartupEvidenceBundle evidence, StartupFailureClassification classification)
            implements WorkerEventPayload {}
}
