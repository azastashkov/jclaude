package org.jclaude.runtime.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/** In-memory worker registry. */
public final class WorkerRegistry {

    private final Object lock = new Object();
    private final Map<String, Worker> workers = new HashMap<>();
    private long counter;

    public WorkerRegistry() {}

    public Worker create(String cwd, List<String> trusted_roots, boolean auto_recover_prompt_misdelivery) {
        synchronized (lock) {
            counter++;
            long ts = now_secs();
            String worker_id = String.format(Locale.ROOT, "worker_%08x_%d", ts, counter);
            boolean trust_auto = false;
            for (String root : trusted_roots) {
                if (path_matches_allowlist(cwd, root)) {
                    trust_auto = true;
                    break;
                }
            }
            Worker worker = new Worker(worker_id, cwd, trust_auto, auto_recover_prompt_misdelivery, ts);
            push_event(
                    worker,
                    WorkerEventKind.SPAWNING,
                    WorkerStatus.SPAWNING,
                    Optional.of("worker created"),
                    Optional.empty());
            workers.put(worker_id, worker);
            return worker;
        }
    }

    public Optional<Worker> get(String worker_id) {
        synchronized (lock) {
            return Optional.ofNullable(workers.get(worker_id));
        }
    }

    public Worker observe(String worker_id, String screen_text) {
        synchronized (lock) {
            Worker worker = require(worker_id);
            String lower = screen_text.toLowerCase(Locale.ROOT);

            Optional<ToolPermissionObservation> tool = detect_tool_permission_prompt(screen_text, lower);
            if (tool.isPresent()) {
                ToolPermissionObservation t = tool.get();
                worker.set_status(WorkerStatus.TOOL_PERMISSION_REQUIRED);
                worker.set_last_error(Optional.of(
                        new WorkerFailure(WorkerFailureKind.TOOL_PERMISSION_GATE, t.message(), now_secs())));
                push_event(
                        worker,
                        WorkerEventKind.TOOL_PERMISSION_REQUIRED,
                        WorkerStatus.TOOL_PERMISSION_REQUIRED,
                        Optional.of("tool permission prompt detected"),
                        Optional.of(new WorkerEventPayload.ToolPermissionPrompt(
                                t.server_name(), t.tool_name(), 0L, t.allow_scope(), t.prompt_preview())));
                return worker;
            }

            if (!worker.trust_gate_cleared() && detect_trust_prompt(lower)) {
                worker.set_status(WorkerStatus.TRUST_REQUIRED);
                worker.set_last_error(Optional.of(new WorkerFailure(
                        WorkerFailureKind.TRUST_GATE, "worker boot blocked on trust prompt", now_secs())));
                push_event(
                        worker,
                        WorkerEventKind.TRUST_REQUIRED,
                        WorkerStatus.TRUST_REQUIRED,
                        Optional.of("trust prompt detected"),
                        Optional.of(new WorkerEventPayload.TrustPrompt(worker.cwd(), Optional.empty())));

                if (worker.trust_auto_resolve()) {
                    worker.set_trust_gate_cleared(true);
                    worker.set_last_error(Optional.empty());
                    worker.set_status(WorkerStatus.SPAWNING);
                    push_event(
                            worker,
                            WorkerEventKind.TRUST_RESOLVED,
                            WorkerStatus.SPAWNING,
                            Optional.of("allowlisted repo auto-resolved trust prompt"),
                            Optional.of(new WorkerEventPayload.TrustPrompt(
                                    worker.cwd(), Optional.of(WorkerTrustResolution.AUTO_ALLOWLISTED))));
                } else {
                    return worker;
                }
            }

            if (prompt_misdelivery_is_relevant(worker)) {
                Optional<PromptDeliveryObservation> obs = detect_prompt_misdelivery(
                        screen_text,
                        lower,
                        worker.last_prompt().orElse(null),
                        worker.cwd(),
                        worker.expected_receipt().orElse(null));
                if (obs.isPresent()) {
                    PromptDeliveryObservation observation = obs.get();
                    String preview = prompt_preview(worker.last_prompt().orElse(""));
                    String message =
                            switch (observation.target()) {
                                case SHELL -> "worker prompt landed in shell instead of coding agent: " + preview;
                                case WRONG_TARGET -> "worker prompt landed in the wrong target instead of "
                                        + worker.cwd() + ": " + preview;
                                case WRONG_TASK -> "worker prompt receipt mismatched the expected task context for "
                                        + worker.cwd() + ": " + preview;
                                case UNKNOWN -> "worker prompt delivery failed before reaching coding agent: "
                                        + preview;
                            };
                    worker.set_last_error(
                            Optional.of(new WorkerFailure(WorkerFailureKind.PROMPT_DELIVERY, message, now_secs())));
                    worker.set_prompt_in_flight(false);
                    push_event(
                            worker,
                            WorkerEventKind.PROMPT_MISDELIVERY,
                            WorkerStatus.FAILED,
                            Optional.of(prompt_misdelivery_detail(observation)),
                            Optional.of(new WorkerEventPayload.PromptDelivery(
                                    preview,
                                    observation.target(),
                                    observation.observed_cwd(),
                                    observation.observed_prompt_preview(),
                                    worker.expected_receipt(),
                                    false)));
                    if (worker.auto_recover_prompt_misdelivery()) {
                        worker.set_replay_prompt(worker.last_prompt());
                        worker.set_status(WorkerStatus.READY_FOR_PROMPT);
                        push_event(
                                worker,
                                WorkerEventKind.PROMPT_REPLAY_ARMED,
                                WorkerStatus.READY_FOR_PROMPT,
                                Optional.of("prompt replay armed after prompt misdelivery"),
                                Optional.of(new WorkerEventPayload.PromptDelivery(
                                        preview,
                                        observation.target(),
                                        observation.observed_cwd(),
                                        observation.observed_prompt_preview(),
                                        worker.expected_receipt(),
                                        true)));
                    } else {
                        worker.set_status(WorkerStatus.FAILED);
                    }
                    return worker;
                }
            }

            if (detect_running_cue(lower) && worker.prompt_in_flight()) {
                worker.set_prompt_in_flight(false);
                worker.set_status(WorkerStatus.RUNNING);
                worker.set_last_error(Optional.empty());
            }

            if (detect_ready_for_prompt(screen_text, lower) && worker.status() != WorkerStatus.READY_FOR_PROMPT) {
                worker.set_status(WorkerStatus.READY_FOR_PROMPT);
                worker.set_prompt_in_flight(false);
                if (worker.last_error().map(WorkerFailure::kind).orElse(null) == WorkerFailureKind.TRUST_GATE) {
                    worker.set_last_error(Optional.empty());
                }
                push_event(
                        worker,
                        WorkerEventKind.READY_FOR_PROMPT,
                        WorkerStatus.READY_FOR_PROMPT,
                        Optional.of("worker is ready for prompt delivery"),
                        Optional.empty());
            }
            return worker;
        }
    }

    public Worker resolve_trust(String worker_id) {
        synchronized (lock) {
            Worker worker = require(worker_id);
            if (worker.status() != WorkerStatus.TRUST_REQUIRED) {
                throw new IllegalStateException("worker " + worker_id + " is not waiting on trust; current status: "
                        + worker.status().display());
            }
            worker.set_trust_gate_cleared(true);
            worker.set_last_error(Optional.empty());
            worker.set_status(WorkerStatus.SPAWNING);
            push_event(
                    worker,
                    WorkerEventKind.TRUST_RESOLVED,
                    WorkerStatus.SPAWNING,
                    Optional.of("trust prompt resolved manually"),
                    Optional.of(new WorkerEventPayload.TrustPrompt(
                            worker.cwd(), Optional.of(WorkerTrustResolution.MANUAL_APPROVAL))));
            return worker;
        }
    }

    public Worker send_prompt(String worker_id, String prompt, WorkerTaskReceipt task_receipt) {
        synchronized (lock) {
            Worker worker = require(worker_id);
            if (worker.status() != WorkerStatus.READY_FOR_PROMPT) {
                throw new IllegalStateException(
                        "worker " + worker_id + " is not ready for prompt delivery; current status: "
                                + worker.status().display());
            }

            String next = null;
            if (prompt != null) {
                String trimmed = prompt.trim();
                if (!trimmed.isEmpty()) {
                    next = trimmed;
                }
            }
            if (next == null) {
                next = worker.replay_prompt()
                        .orElseThrow(() ->
                                new IllegalStateException("worker " + worker_id + " has no prompt to send or replay"));
            }

            worker.increment_prompt_attempts();
            worker.set_prompt_in_flight(true);
            worker.set_last_prompt(Optional.of(next));
            worker.set_expected_receipt(task_receipt == null ? Optional.empty() : Optional.of(task_receipt));
            worker.set_replay_prompt(Optional.empty());
            worker.set_last_error(Optional.empty());
            worker.set_status(WorkerStatus.RUNNING);
            push_event(
                    worker,
                    WorkerEventKind.RUNNING,
                    WorkerStatus.RUNNING,
                    Optional.of("prompt dispatched to worker: " + prompt_preview(next)),
                    Optional.empty());
            return worker;
        }
    }

    public WorkerReadySnapshot await_ready(String worker_id) {
        synchronized (lock) {
            Worker worker = require(worker_id);
            boolean ready = worker.status() == WorkerStatus.READY_FOR_PROMPT;
            boolean blocked = worker.status() == WorkerStatus.TRUST_REQUIRED
                    || worker.status() == WorkerStatus.TOOL_PERMISSION_REQUIRED
                    || worker.status() == WorkerStatus.FAILED;
            return new WorkerReadySnapshot(
                    worker.worker_id(),
                    worker.status(),
                    ready,
                    blocked,
                    worker.replay_prompt().isPresent(),
                    worker.last_error());
        }
    }

    public Worker restart(String worker_id) {
        synchronized (lock) {
            Worker worker = require(worker_id);
            worker.set_status(WorkerStatus.SPAWNING);
            worker.set_trust_gate_cleared(false);
            worker.set_last_prompt(Optional.empty());
            worker.set_replay_prompt(Optional.empty());
            worker.set_last_error(Optional.empty());
            worker.reset_prompt_attempts();
            worker.set_prompt_in_flight(false);
            push_event(
                    worker,
                    WorkerEventKind.RESTARTED,
                    WorkerStatus.SPAWNING,
                    Optional.of("worker restarted"),
                    Optional.empty());
            return worker;
        }
    }

    public Worker terminate(String worker_id) {
        synchronized (lock) {
            Worker worker = require(worker_id);
            worker.set_status(WorkerStatus.FINISHED);
            worker.set_prompt_in_flight(false);
            push_event(
                    worker,
                    WorkerEventKind.FINISHED,
                    WorkerStatus.FINISHED,
                    Optional.of("worker terminated by control plane"),
                    Optional.empty());
            return worker;
        }
    }

    public Worker observe_completion(String worker_id, String finish_reason, long tokens_output) {
        synchronized (lock) {
            Worker worker = require(worker_id);
            boolean is_provider_failure =
                    (finish_reason.equals("unknown") && tokens_output == 0) || finish_reason.equals("error");
            if (is_provider_failure) {
                String message = finish_reason.equals("unknown") && tokens_output == 0
                        ? "session completed with finish='unknown' and zero output — provider degraded or context exhausted"
                        : "session failed with finish='" + finish_reason + "' — provider error";
                worker.set_last_error(Optional.of(new WorkerFailure(WorkerFailureKind.PROVIDER, message, now_secs())));
                worker.set_status(WorkerStatus.FAILED);
                worker.set_prompt_in_flight(false);
                push_event(
                        worker,
                        WorkerEventKind.FAILED,
                        WorkerStatus.FAILED,
                        Optional.of("provider failure classified"),
                        Optional.empty());
            } else {
                worker.set_status(WorkerStatus.FINISHED);
                worker.set_prompt_in_flight(false);
                worker.set_last_error(Optional.empty());
                push_event(
                        worker,
                        WorkerEventKind.FINISHED,
                        WorkerStatus.FINISHED,
                        Optional.of("session completed: finish='" + finish_reason + "', tokens=" + tokens_output),
                        Optional.empty());
            }
            return worker;
        }
    }

    public Worker observe_startup_timeout(
            String worker_id, String pane_command, boolean transport_healthy, boolean mcp_healthy) {
        synchronized (lock) {
            Worker worker = require(worker_id);
            long now = now_secs();
            long elapsed = Math.max(0, now - worker.created_at());

            WorkerEvent latest_tool_perm = null;
            for (int i = worker.events().size() - 1; i >= 0; i--) {
                if (worker.events().get(i).kind() == WorkerEventKind.TOOL_PERMISSION_REQUIRED) {
                    latest_tool_perm = worker.events().get(i);
                    break;
                }
            }
            Optional<ToolPermissionAllowScope> allow_scope = Optional.empty();
            if (latest_tool_perm != null
                    && latest_tool_perm.payload().isPresent()
                    && latest_tool_perm.payload().get() instanceof WorkerEventPayload.ToolPermissionPrompt p) {
                allow_scope = Optional.of(p.allow_scope());
            }

            boolean trust_detected = worker.events().stream().anyMatch(e -> e.kind() == WorkerEventKind.TRUST_REQUIRED);
            boolean tool_detected =
                    worker.events().stream().anyMatch(e -> e.kind() == WorkerEventKind.TOOL_PERMISSION_REQUIRED);

            StartupEvidenceBundle evidence = new StartupEvidenceBundle(
                    worker.status(),
                    pane_command,
                    worker.prompt_delivery_attempts() > 0 ? Optional.of(worker.updated_at()) : Optional.empty(),
                    worker.status() == WorkerStatus.RUNNING && !worker.prompt_in_flight(),
                    trust_detected,
                    tool_detected,
                    latest_tool_perm == null
                            ? Optional.empty()
                            : Optional.of(Math.max(0, now - latest_tool_perm.timestamp())),
                    allow_scope,
                    transport_healthy,
                    mcp_healthy,
                    elapsed);

            StartupFailureClassification classification = classify_startup_failure(evidence);

            worker.set_last_error(Optional.of(new WorkerFailure(
                    WorkerFailureKind.STARTUP_NO_EVIDENCE,
                    "worker startup stalled after " + elapsed + "s — classified as " + classification.name(),
                    now)));
            worker.set_status(WorkerStatus.FAILED);
            worker.set_prompt_in_flight(false);
            push_event(
                    worker,
                    WorkerEventKind.STARTUP_NO_EVIDENCE,
                    WorkerStatus.FAILED,
                    Optional.of("startup timeout with evidence: last_state="
                            + evidence.last_lifecycle_state().display()
                            + ", trust_detected=" + evidence.trust_prompt_detected()
                            + ", prompt_accepted=" + evidence.prompt_acceptance_state()),
                    Optional.of(new WorkerEventPayload.StartupNoEvidence(evidence, classification)));
            return worker;
        }
    }

    static StartupFailureClassification classify_startup_failure(StartupEvidenceBundle evidence) {
        if (!evidence.transport_healthy()) {
            return StartupFailureClassification.TRANSPORT_DEAD;
        }
        if (evidence.trust_prompt_detected() && evidence.last_lifecycle_state() == WorkerStatus.TRUST_REQUIRED) {
            return StartupFailureClassification.TRUST_REQUIRED;
        }
        if (evidence.tool_permission_prompt_detected()
                && evidence.last_lifecycle_state() == WorkerStatus.TOOL_PERMISSION_REQUIRED) {
            return StartupFailureClassification.TOOL_PERMISSION_REQUIRED;
        }
        if (evidence.prompt_sent_at().isPresent()
                && !evidence.prompt_acceptance_state()
                && evidence.last_lifecycle_state() == WorkerStatus.RUNNING) {
            return StartupFailureClassification.PROMPT_ACCEPTANCE_TIMEOUT;
        }
        if (evidence.prompt_sent_at().isPresent()
                && !evidence.prompt_acceptance_state()
                && evidence.elapsed_seconds() > 30) {
            return StartupFailureClassification.PROMPT_MISDELIVERY;
        }
        if (!evidence.mcp_healthy() && evidence.transport_healthy()) {
            return StartupFailureClassification.WORKER_CRASHED;
        }
        return StartupFailureClassification.UNKNOWN;
    }

    private Worker require(String worker_id) {
        Worker w = workers.get(worker_id);
        if (w == null) {
            throw new NoSuchElementException("worker not found: " + worker_id);
        }
        return w;
    }

    private static long now_secs() {
        return System.currentTimeMillis() / 1000L;
    }

    private static void push_event(
            Worker worker,
            WorkerEventKind kind,
            WorkerStatus status,
            Optional<String> detail,
            Optional<WorkerEventPayload> payload) {
        long ts = now_secs();
        long seq = worker.events().size() + 1L;
        worker.set_updated_at(ts);
        worker.set_status(status);
        worker.add_event(new WorkerEvent(seq, kind, status, detail, payload, ts));
        emit_state_file(worker);
    }

    private static void emit_state_file(Worker worker) {
        Path state_dir = Paths.get(worker.cwd()).resolve(".claw");
        try {
            Files.createDirectories(state_dir);
        } catch (IOException e) {
            return;
        }
        Path state_path = state_dir.resolve("worker-state.json");
        Path tmp_path = state_dir.resolve("worker-state.json.tmp");
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode snapshot = json.createObjectNode();
        snapshot.put("worker_id", worker.worker_id());
        snapshot.put("status", worker.status().display());
        snapshot.put("is_ready", worker.status() == WorkerStatus.READY_FOR_PROMPT);
        snapshot.put("trust_gate_cleared", worker.trust_gate_cleared());
        snapshot.put("prompt_in_flight", worker.prompt_in_flight());
        if (!worker.events().isEmpty()) {
            snapshot.put(
                    "last_event",
                    worker.events().get(worker.events().size() - 1).kind().name());
        }
        snapshot.put("updated_at", worker.updated_at());
        snapshot.put("seconds_since_update", Math.max(0, now_secs() - worker.updated_at()));
        try {
            Files.writeString(tmp_path, json.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
            Files.move(
                    tmp_path,
                    state_path,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // best-effort
        }
    }

    private static boolean path_matches_allowlist(String cwd, String trusted_root) {
        Path c = Paths.get(cwd);
        Path r = Paths.get(trusted_root);
        return c.equals(r) || c.startsWith(r);
    }

    private static boolean prompt_misdelivery_is_relevant(Worker worker) {
        return worker.prompt_in_flight() && worker.last_prompt().isPresent();
    }

    record ToolPermissionObservation(
            Optional<String> server_name,
            Optional<String> tool_name,
            ToolPermissionAllowScope allow_scope,
            String prompt_preview) {
        String message() {
            if (server_name.isPresent() && tool_name.isPresent()) {
                return "worker boot blocked on tool permission prompt for " + server_name.get() + "." + tool_name.get();
            }
            if (server_name.isPresent()) {
                return "worker boot blocked on tool permission prompt for " + server_name.get();
            }
            if (tool_name.isPresent()) {
                return "worker boot blocked on tool permission prompt for " + tool_name.get();
            }
            return "worker boot blocked on tool permission prompt";
        }
    }

    record PromptDeliveryObservation(
            WorkerPromptTarget target, Optional<String> observed_cwd, Optional<String> observed_prompt_preview) {}

    private static Optional<ToolPermissionObservation> detect_tool_permission_prompt(String screen_text, String lower) {
        boolean looks_like_prompt = lower.contains("allow the")
                && lower.contains("server")
                && lower.contains("tool")
                && lower.contains("run");
        boolean looks_like_tool_gate = lower.contains("allow tool") && lower.contains("run");
        if (!looks_like_prompt && !looks_like_tool_gate) {
            return Optional.empty();
        }

        String matched = null;
        String[] lines = screen_text.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].toLowerCase(Locale.ROOT);
            if (l.contains("allow") && l.contains("tool") && (l.contains("run") || l.contains("server"))) {
                matched = lines[i].trim();
                break;
            }
        }
        final String prompt_line = matched != null ? matched : screen_text.trim();

        Optional<String> tool_name = extract_quoted_value(prompt_line)
                .or(() -> extract_after(prompt_line, "tool ").map(WorkerRegistry::normalize_tool_token));
        Optional<String> server_name = extract_between(prompt_line, "the ", " server")
                .map(s -> s.endsWith(" MCP") ? s.substring(0, s.length() - 4) : s)
                .or(() -> tool_name.flatMap(WorkerRegistry::extract_server_from_qualified_tool));

        return Optional.of(new ToolPermissionObservation(
                server_name, tool_name, detect_tool_permission_allow_scope(lower), prompt_preview(prompt_line)));
    }

    private static ToolPermissionAllowScope detect_tool_permission_allow_scope(String lower) {
        for (String n :
                new String[] {"always allow", "allow always", "allow this tool always", "allow for all sessions"}) {
            if (lower.contains(n)) {
                return ToolPermissionAllowScope.SESSION_OR_ALWAYS;
            }
        }
        for (String n : new String[] {"allow once", "allow for this session", "allow this session", "yes, allow"}) {
            if (lower.contains(n)) {
                return ToolPermissionAllowScope.SESSION_ONLY;
            }
        }
        return ToolPermissionAllowScope.UNKNOWN;
    }

    private static Optional<String> extract_quoted_value(String text) {
        int start = text.indexOf('"');
        if (start < 0) {
            return Optional.empty();
        }
        int end = text.indexOf('"', start + 1);
        if (end < 0) {
            return Optional.empty();
        }
        return Optional.of(text.substring(start + 1, end));
    }

    private static Optional<String> extract_between(String text, String prefix, String suffix) {
        int start = text.indexOf(prefix);
        if (start < 0) {
            return Optional.empty();
        }
        start += prefix.length();
        int end = text.indexOf(suffix, start);
        if (end < 0) {
            return Optional.empty();
        }
        String value = text.substring(start, end).trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private static Optional<String> extract_after(String text, String prefix) {
        int start = text.toLowerCase(Locale.ROOT).indexOf(prefix);
        if (start < 0) {
            return Optional.empty();
        }
        start += prefix.length();
        String[] parts = text.substring(start).split("\\s+");
        if (parts.length == 0) {
            return Optional.empty();
        }
        String value = parts[0].replaceAll("[?:\"']+$", "").replaceAll("^[?:\"']+", "");
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private static String normalize_tool_token(String token) {
        return token.replaceAll("[?:\"']+$", "").replaceAll("^[?:\"']+", "");
    }

    private static Optional<String> extract_server_from_qualified_tool(String tool) {
        if (!tool.startsWith("mcp__")) {
            return Optional.empty();
        }
        String rest = tool.substring("mcp__".length());
        int idx = rest.indexOf("__");
        if (idx < 0) {
            return Optional.empty();
        }
        String server = rest.substring(0, idx);
        return server.isEmpty() ? Optional.empty() : Optional.of(server);
    }

    private static boolean detect_trust_prompt(String lower) {
        for (String needle : new String[] {
            "do you trust the files in this folder",
            "trust the files in this folder",
            "trust this folder",
            "allow and continue",
            "yes, proceed"
        }) {
            if (lower.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    static boolean detect_ready_for_prompt(String screen_text, String lower) {
        for (String n :
                new String[] {"ready for input", "ready for your input", "ready for prompt", "send a message"}) {
            if (lower.contains(n)) {
                return true;
            }
        }
        String[] lines = screen_text.split("\n");
        String last = null;
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) {
                last = lines[i].trim();
                break;
            }
        }
        if (last == null) {
            return false;
        }
        if (is_shell_prompt(last)) {
            return false;
        }
        return last.equals(">")
                || last.equals("›")
                || last.equals("❯")
                || last.startsWith("> ")
                || last.startsWith("› ")
                || last.startsWith("❯ ")
                || last.contains("│ >")
                || last.contains("│ ›")
                || last.contains("│ ❯");
    }

    private static boolean detect_running_cue(String lower) {
        for (String n : new String[] {"thinking", "working", "running tests", "inspecting", "analyzing"}) {
            if (lower.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static boolean is_shell_prompt(String trimmed) {
        return trimmed.endsWith("$")
                || trimmed.endsWith("%")
                || trimmed.endsWith("#")
                || trimmed.startsWith("$")
                || trimmed.startsWith("%")
                || trimmed.startsWith("#");
    }

    private static Optional<PromptDeliveryObservation> detect_prompt_misdelivery(
            String screen_text, String lower, String prompt, String expected_cwd, WorkerTaskReceipt expected_receipt) {
        if (prompt == null) {
            return Optional.empty();
        }
        String prompt_snippet = "";
        for (String line : prompt.split("\n")) {
            if (!line.trim().isEmpty()) {
                prompt_snippet = line.trim().toLowerCase(Locale.ROOT);
                break;
            }
        }
        if (prompt_snippet.isEmpty()) {
            return Optional.empty();
        }
        boolean prompt_visible = lower.contains(prompt_snippet);
        Optional<String> observed_prompt_preview = detect_prompt_echo(screen_text);

        if (expected_receipt != null) {
            boolean receipt_visible = task_receipt_visible(lower, expected_receipt);
            boolean mismatched_prompt_visible = false;
            if (observed_prompt_preview.isPresent()) {
                String preview_lower = observed_prompt_preview.get().toLowerCase(Locale.ROOT);
                mismatched_prompt_visible = !preview_lower.contains(prompt_snippet);
            }
            if ((prompt_visible || mismatched_prompt_visible) && !receipt_visible) {
                return Optional.of(new PromptDeliveryObservation(
                        WorkerPromptTarget.WRONG_TASK,
                        detect_observed_shell_cwd(screen_text),
                        observed_prompt_preview));
            }
        }

        Optional<String> observed_cwd = detect_observed_shell_cwd(screen_text);
        if (observed_cwd.isPresent()
                && prompt_visible
                && !cwd_matches_observed_target(expected_cwd, observed_cwd.get())) {
            return Optional.of(new PromptDeliveryObservation(
                    WorkerPromptTarget.WRONG_TARGET, observed_cwd, observed_prompt_preview));
        }

        boolean shell_error = false;
        for (String n : new String[] {
            "command not found",
            "syntax error near unexpected token",
            "parse error near",
            "no such file or directory",
            "unknown command"
        }) {
            if (lower.contains(n)) {
                shell_error = true;
                break;
            }
        }
        if (shell_error && prompt_visible) {
            return Optional.of(
                    new PromptDeliveryObservation(WorkerPromptTarget.SHELL, Optional.empty(), observed_prompt_preview));
        }
        return Optional.empty();
    }

    private static boolean task_receipt_visible(String lower_screen, WorkerTaskReceipt receipt) {
        String[] tokens = {
            receipt.repo().toLowerCase(Locale.ROOT),
            receipt.task_kind().toLowerCase(Locale.ROOT),
            receipt.source_surface().toLowerCase(Locale.ROOT),
            receipt.objective_preview().toLowerCase(Locale.ROOT)
        };
        for (String t : tokens) {
            if (!lower_screen.contains(t)) {
                return false;
            }
        }
        for (String a : receipt.expected_artifacts()) {
            if (!lower_screen.contains(a.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static String prompt_misdelivery_detail(PromptDeliveryObservation observation) {
        return switch (observation.target()) {
            case SHELL -> "shell misdelivery detected";
            case WRONG_TARGET -> "prompt landed in wrong target";
            case WRONG_TASK -> "prompt receipt mismatched expected task context";
            case UNKNOWN -> "prompt delivery failure detected";
        };
    }

    private static Optional<String> detect_observed_shell_cwd(String screen_text) {
        for (String line : screen_text.split("\n")) {
            String[] tokens = line.split("\\s+");
            int prompt_idx = -1;
            for (int i = 0; i < tokens.length; i++) {
                if (is_shell_prompt_token(tokens[i])) {
                    prompt_idx = i;
                    break;
                }
            }
            if (prompt_idx > 0) {
                String candidate = tokens[prompt_idx - 1];
                if (looks_like_cwd_label(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean is_shell_prompt_token(String t) {
        return t.equals("$") || t.equals("%") || t.equals("#") || t.equals(">") || t.equals("›") || t.equals("❯");
    }

    private static boolean looks_like_cwd_label(String c) {
        return c.startsWith("/") || c.startsWith("~") || c.startsWith(".") || c.contains("/");
    }

    private static boolean cwd_matches_observed_target(String expected_cwd, String observed_cwd) {
        Path expected = Paths.get(expected_cwd);
        String expected_base =
                expected.getFileName() != null ? expected.getFileName().toString() : expected.toString();
        Path observed = Paths.get(observed_cwd);
        String observed_base = observed.getFileName() != null
                ? observed.getFileName().toString()
                : observed_cwd.replaceAll("^:|:$", "");
        return expected.toString().endsWith(observed_cwd)
                || observed_cwd.endsWith(expected.toString())
                || expected_base.equals(observed_base);
    }

    private static Optional<String> detect_prompt_echo(String screen_text) {
        for (String line : screen_text.split("\n")) {
            String trimmed = line.replaceAll("^\\s+", "");
            if (trimmed.startsWith("›")) {
                String value = trimmed.substring(1).trim();
                if (!value.isEmpty()) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    static String prompt_preview(String prompt) {
        String trimmed = prompt.trim();
        if (trimmed.length() <= 48) {
            return trimmed;
        }
        return trimmed.substring(0, 48).stripTrailing() + "…";
    }
}
