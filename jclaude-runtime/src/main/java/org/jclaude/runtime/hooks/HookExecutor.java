package org.jclaude.runtime.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Runs hook commands and aggregates results. */
public final class HookExecutor {

    private static final int HOOK_PREVIEW_CHAR_LIMIT = 160;
    private static final ObjectMapper JSON = new ObjectMapper();

    public HookRunResult run(
            HookEvent event,
            String tool_name,
            String tool_input,
            List<String> commands,
            HookAbortSignal abort,
            Consumer<HookProgressEvent> reporter) {
        List<String> messages = new ArrayList<>();
        boolean denied = false;
        boolean failed = false;
        boolean cancelled = false;
        Optional<String> updated_input = Optional.empty();
        Optional<String> permission_override = Optional.empty();
        Optional<String> permission_reason = Optional.empty();

        for (String command : commands) {
            if (abort != null && abort.is_aborted()) {
                cancelled = true;
                if (reporter != null) {
                    reporter.accept(new HookProgressEvent.Cancelled(event, tool_name, command));
                }
                break;
            }
            if (reporter != null) {
                reporter.accept(new HookProgressEvent.Started(event, tool_name, command));
            }

            try {
                ObjectNode payload = JSON.createObjectNode();
                payload.put("event", event.as_str());
                payload.put("tool_name", tool_name);
                payload.put("tool_input", tool_input);

                ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (OutputStream stdin = p.getOutputStream()) {
                    stdin.write(JSON.writeValueAsBytes(payload));
                }
                String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                boolean ok = p.waitFor(60, TimeUnit.SECONDS);
                if (!ok) {
                    p.destroyForcibly();
                    failed = true;
                    messages.add("hook timed out: " + truncate(command));
                    continue;
                }
                int exit = p.exitValue();
                if (exit != 0) {
                    failed = true;
                    messages.add("hook failed: " + truncate(command));
                    continue;
                }
                if (!stdout.isBlank()) {
                    try {
                        JsonNode node = JSON.readTree(stdout);
                        if (node.has("deny") && node.get("deny").asBoolean()) {
                            denied = true;
                        }
                        if (node.has("message")) {
                            messages.add(node.get("message").asText());
                        }
                        if (node.has("updated_input")
                                && node.get("updated_input").isTextual()) {
                            updated_input =
                                    Optional.of(node.get("updated_input").asText());
                        }
                        if (node.has("permission_override")
                                && node.get("permission_override").isTextual()) {
                            permission_override =
                                    Optional.of(node.get("permission_override").asText());
                        }
                        if (node.has("permission_reason")
                                && node.get("permission_reason").isTextual()) {
                            permission_reason =
                                    Optional.of(node.get("permission_reason").asText());
                        }
                    } catch (IOException e) {
                        messages.add(truncate(stdout));
                    }
                }
                if (reporter != null) {
                    reporter.accept(new HookProgressEvent.Completed(event, tool_name, command));
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                failed = true;
                messages.add("hook error: " + e.getMessage());
            }
        }

        return new HookRunResult(
                denied, failed, cancelled, messages, permission_override, permission_reason, updated_input);
    }

    private static String truncate(String s) {
        if (s.length() <= HOOK_PREVIEW_CHAR_LIMIT) {
            return s;
        }
        return s.substring(0, HOOK_PREVIEW_CHAR_LIMIT) + "…";
    }
}
