package org.jclaude.runtime.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerRegistryTest {

    @Test
    void allowlisted_trust_prompt_auto_resolves_then_reaches_ready_state(@TempDir Path tmp) {
        WorkerRegistry registry = new WorkerRegistry();
        Worker worker = registry.create(tmp.toString(), List.of(tmp.toString()), true);

        Worker after_trust =
                registry.observe(worker.worker_id(), "Do you trust the files in this folder?\n1. Yes, proceed\n2. No");
        assertThat(after_trust.status()).isEqualTo(WorkerStatus.SPAWNING);
        assertThat(after_trust.trust_gate_cleared()).isTrue();
        assertThat(after_trust.events()).anyMatch(e -> e.kind() == WorkerEventKind.TRUST_REQUIRED);
        assertThat(after_trust.events()).anyMatch(e -> e.kind() == WorkerEventKind.TRUST_RESOLVED);

        Worker ready = registry.observe(worker.worker_id(), "Ready for your input\n>");
        assertThat(ready.status()).isEqualTo(WorkerStatus.READY_FOR_PROMPT);
        assertThat(ready.last_error()).isEmpty();
    }

    @Test
    void trust_prompt_blocks_non_allowlisted_worker_until_resolved(@TempDir Path tmp) {
        WorkerRegistry registry = new WorkerRegistry();
        Worker worker = registry.create(tmp.toString(), List.of(), true);

        Worker blocked =
                registry.observe(worker.worker_id(), "Do you trust the files in this folder?\n1. Yes, proceed\n2. No");
        assertThat(blocked.status()).isEqualTo(WorkerStatus.TRUST_REQUIRED);
        assertThat(blocked.last_error()).isPresent();
        assertThat(blocked.last_error().get().kind()).isEqualTo(WorkerFailureKind.TRUST_GATE);

        assertThatThrownBy(() -> registry.send_prompt(worker.worker_id(), "ship it", null))
                .hasMessageContaining("not ready for prompt delivery");

        Worker resolved = registry.resolve_trust(worker.worker_id());
        assertThat(resolved.status()).isEqualTo(WorkerStatus.SPAWNING);
        assertThat(resolved.trust_gate_cleared()).isTrue();
    }

    @Test
    void ready_detection_ignores_plain_shell_prompts() {
        assertThat(WorkerRegistry.detect_ready_for_prompt("bellman@host %", "bellman@host %"))
                .isFalse();
        assertThat(WorkerRegistry.detect_ready_for_prompt("/tmp/repo $", "/tmp/repo $"))
                .isFalse();
        assertThat(WorkerRegistry.detect_ready_for_prompt("│ >", "│ >")).isTrue();
    }

    @Test
    void tool_permission_prompt_blocks_worker_with_structured_event(@TempDir Path tmp) {
        WorkerRegistry registry = new WorkerRegistry();
        Worker worker = registry.create(tmp.toString(), List.of(), true);

        Worker blocked = registry.observe(
                worker.worker_id(),
                "Allow the omx_memory MCP server to run tool \"project_memory_read\"?\n"
                        + "1. Yes, allow once\n"
                        + "2. Always allow this tool");

        assertThat(blocked.status()).isEqualTo(WorkerStatus.TOOL_PERMISSION_REQUIRED);
        assertThat(blocked.last_error()).isPresent();
        assertThat(blocked.last_error().get().kind()).isEqualTo(WorkerFailureKind.TOOL_PERMISSION_GATE);

        WorkerReadySnapshot readiness = registry.await_ready(worker.worker_id());
        assertThat(readiness.blocked()).isTrue();
        assertThat(readiness.ready()).isFalse();
    }

    @Test
    void await_ready_surfaces_blocked_or_ready_worker_state(@TempDir Path tmp) {
        WorkerRegistry registry = new WorkerRegistry();
        Worker worker = registry.create(tmp.toString(), List.of(), false);

        WorkerReadySnapshot initial = registry.await_ready(worker.worker_id());
        assertThat(initial.ready()).isFalse();
        assertThat(initial.blocked()).isFalse();

        registry.observe(worker.worker_id(), "Do you trust the files in this folder?\n1. Yes, proceed\n2. No");
        WorkerReadySnapshot blocked = registry.await_ready(worker.worker_id());
        assertThat(blocked.ready()).isFalse();
        assertThat(blocked.blocked()).isTrue();

        registry.resolve_trust(worker.worker_id());
        registry.observe(worker.worker_id(), "Ready for your input\n>");
        WorkerReadySnapshot ready = registry.await_ready(worker.worker_id());
        assertThat(ready.ready()).isTrue();
        assertThat(ready.blocked()).isFalse();
        assertThat(ready.last_error()).isEmpty();
    }

    @Test
    void restart_and_terminate_reset_or_finish_worker(@TempDir Path tmp) {
        WorkerRegistry registry = new WorkerRegistry();
        Worker worker = registry.create(tmp.toString(), List.of(), true);
        registry.observe(worker.worker_id(), "Ready for input\n>");
        registry.send_prompt(worker.worker_id(), "Run tests", null);

        Worker restarted = registry.restart(worker.worker_id());
        assertThat(restarted.status()).isEqualTo(WorkerStatus.SPAWNING);
        assertThat(restarted.prompt_delivery_attempts()).isEqualTo(0);
        assertThat(restarted.last_prompt()).isEmpty();
        assertThat(restarted.prompt_in_flight()).isFalse();

        Worker finished = registry.terminate(worker.worker_id());
        assertThat(finished.status()).isEqualTo(WorkerStatus.FINISHED);
        assertThat(finished.events()).anyMatch(e -> e.kind() == WorkerEventKind.FINISHED);
    }

    @Test
    void observe_completion_classifies_provider_failure_on_unknown_finish_zero_tokens(@TempDir Path tmp) {
        WorkerRegistry registry = new WorkerRegistry();
        Worker worker = registry.create(tmp.toString(), List.of(), true);
        registry.observe(worker.worker_id(), "Ready for input\n>");
        registry.send_prompt(worker.worker_id(), "Run tests", null);

        Worker failed = registry.observe_completion(worker.worker_id(), "unknown", 0);

        assertThat(failed.status()).isEqualTo(WorkerStatus.FAILED);
        assertThat(failed.last_error()).isPresent();
        assertThat(failed.last_error().get().kind()).isEqualTo(WorkerFailureKind.PROVIDER);
        assertThat(failed.last_error().get().message()).contains("provider degraded");
        assertThat(failed.events()).anyMatch(e -> e.kind() == WorkerEventKind.FAILED);
    }

    @Test
    void observe_completion_accepts_normal_finish_with_tokens(@TempDir Path tmp) {
        WorkerRegistry registry = new WorkerRegistry();
        Worker worker = registry.create(tmp.toString(), List.of(), true);
        registry.observe(worker.worker_id(), "Ready for input\n>");
        registry.send_prompt(worker.worker_id(), "Run tests", null);

        Worker finished = registry.observe_completion(worker.worker_id(), "stop", 150);

        assertThat(finished.status()).isEqualTo(WorkerStatus.FINISHED);
        assertThat(finished.last_error()).isEmpty();
        assertThat(finished.events()).anyMatch(e -> e.kind() == WorkerEventKind.FINISHED);
    }

    @Test
    void startup_timeout_emits_evidence_bundle_with_classification(@TempDir Path tmp) {
        WorkerRegistry registry = new WorkerRegistry();
        Worker worker = registry.create(tmp.toString(), List.of(), true);

        Worker timed_out = registry.observe_startup_timeout(worker.worker_id(), "cargo test", false, true);

        assertThat(timed_out.status()).isEqualTo(WorkerStatus.FAILED);
        assertThat(timed_out.last_error()).isPresent();
        assertThat(timed_out.last_error().get().kind()).isEqualTo(WorkerFailureKind.STARTUP_NO_EVIDENCE);
        assertThat(timed_out.last_error().get().message()).contains("TRANSPORT_DEAD");

        WorkerEvent event = timed_out.events().stream()
                .filter(e -> e.kind() == WorkerEventKind.STARTUP_NO_EVIDENCE)
                .findFirst()
                .orElseThrow();
        assertThat(event.payload()).isPresent();
        assertThat(event.payload().get()).isInstanceOf(WorkerEventPayload.StartupNoEvidence.class);
        WorkerEventPayload.StartupNoEvidence p =
                (WorkerEventPayload.StartupNoEvidence) event.payload().get();
        assertThat(p.classification()).isEqualTo(StartupFailureClassification.TRANSPORT_DEAD);
        assertThat(p.evidence().pane_command()).isEqualTo("cargo test");
        assertThat(p.evidence().transport_healthy()).isFalse();
    }

    @Test
    void classify_startup_failure_detects_transport_dead() {
        StartupEvidenceBundle evidence = new StartupEvidenceBundle(
                WorkerStatus.SPAWNING,
                "test",
                java.util.Optional.empty(),
                false,
                false,
                false,
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                false,
                true,
                30);

        StartupFailureClassification classification = WorkerRegistry.classify_startup_failure(evidence);

        assertThat(classification).isEqualTo(StartupFailureClassification.TRANSPORT_DEAD);
    }

    @Test
    void classify_startup_failure_defaults_to_unknown() {
        StartupEvidenceBundle evidence = new StartupEvidenceBundle(
                WorkerStatus.SPAWNING,
                "test",
                java.util.Optional.empty(),
                false,
                false,
                false,
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                true,
                true,
                10);

        StartupFailureClassification classification = WorkerRegistry.classify_startup_failure(evidence);

        assertThat(classification).isEqualTo(StartupFailureClassification.UNKNOWN);
    }

    @Test
    void classify_startup_failure_detects_worker_crashed() {
        StartupEvidenceBundle evidence = new StartupEvidenceBundle(
                WorkerStatus.SPAWNING,
                "test",
                java.util.Optional.empty(),
                false,
                false,
                false,
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                true,
                false,
                45);

        StartupFailureClassification classification = WorkerRegistry.classify_startup_failure(evidence);

        assertThat(classification).isEqualTo(StartupFailureClassification.WORKER_CRASHED);
    }
}
