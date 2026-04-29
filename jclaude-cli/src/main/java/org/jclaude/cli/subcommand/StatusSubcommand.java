package org.jclaude.cli.subcommand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.api.providers.Providers;
import org.jclaude.cli.OutputFormat;
import org.jclaude.cli.PermissionModeOption;
import org.jclaude.runtime.session.Session;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code jclaude status} — current session status: model, permission mode, allowed tools, session
 * id, message count, and token usage. Mirrors Rust {@code handle_status_command} dispatch +
 * {@code format_status_text}/{@code format_status_json}.
 */
@Command(
        name = "status",
        mixinStandardHelpOptions = true,
        description = "Show current model, permission mode, session, and tools.")
public final class StatusSubcommand implements Callable<Integer> {

    @Option(
            names = {"--output-format"},
            description = "Output format: text or json (default: ${DEFAULT-VALUE}).",
            defaultValue = "text")
    private String output_format;

    @Option(
            names = {"--model"},
            description = "Model id or alias (default: ${DEFAULT-VALUE}).",
            defaultValue = "claude-sonnet-4-6")
    private String model;

    @Option(
            names = {"--permission-mode"},
            description = "Permission mode: read-only, workspace-write, or danger-full-access.",
            defaultValue = "read-only")
    private String permission_mode;

    @Option(
            names = {"--allowedTools"},
            description = "Comma-separated tool name whitelist.")
    private String allowed_tools_csv;

    @Option(
            names = {"--resume"},
            description = "Resume a session from this id or path (a JSONL session log).")
    private String resume;

    @Override
    public Integer call() {
        OutputFormat fmt = OutputFormat.parse(output_format);
        PermissionModeOption mode = PermissionModeOption.parse(permission_mode);
        String resolved_model = Providers.resolve_model_alias(model);

        StatusReport report = collect(resolved_model, mode, allowed_tools_csv, resume);
        if (fmt == OutputFormat.JSON) {
            System.out.println(format_status_json(report));
        } else {
            System.out.println(format_status_text(report));
        }
        return 0;
    }

    static StatusReport collect(
            String resolved_model, PermissionModeOption mode, String allowed_tools_csv, String resume_path) {
        List<String> allowed_tools = parse_tools_csv(allowed_tools_csv);

        Optional<String> session_id = Optional.empty();
        int message_count = 0;
        Optional<String> session_model = Optional.empty();
        if (resume_path != null && !resume_path.isBlank()) {
            try {
                Path p = Paths.get(resume_path);
                Session loaded = Session.load_from_path(p);
                session_id = Optional.of(loaded.session_id());
                message_count = loaded.messages().size();
                session_model = loaded.model();
            } catch (Exception ignored) {
                // best-effort enrichment when the session can't be loaded
            }
        }

        return new StatusReport(
                session_model.orElse(resolved_model),
                mode.wire(),
                allowed_tools,
                session_id,
                message_count,
                /* input_tokens */ 0L,
                /* output_tokens */ 0L);
    }

    static String format_status_text(StatusReport r) {
        List<String> lines = new ArrayList<>();
        lines.add("Status");
        lines.add("  Model            " + r.model());
        lines.add("  Permission mode  " + r.permission_mode());
        lines.add(
                "  Allowed tools    " + (r.allowed_tools().isEmpty() ? "(all)" : String.join(", ", r.allowed_tools())));
        lines.add("  Session id       " + r.session_id().orElse("(none)"));
        lines.add("  Messages         " + r.message_count());
        lines.add("  Input tokens     " + r.input_tokens());
        lines.add("  Output tokens    " + r.output_tokens());
        return String.join("\n", lines);
    }

    static String format_status_json(StatusReport r) {
        ObjectMapper mapper = JclaudeMappers.standard();
        ObjectNode root = mapper.createObjectNode();
        root.put("kind", "status");
        root.put("model", r.model());
        root.put("permission_mode", r.permission_mode());
        ArrayNode tools = root.putArray("allowed_tools");
        for (String t : r.allowed_tools()) {
            tools.add(t);
        }
        if (r.session_id().isPresent()) {
            root.put("session_id", r.session_id().get());
        } else {
            root.putNull("session_id");
        }
        root.put("message_count", r.message_count());
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", r.input_tokens());
        usage.put("output_tokens", r.output_tokens());
        return root.toPrettyString();
    }

    private static List<String> parse_tools_csv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String chunk : csv.split(",")) {
            String t = chunk.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Internal report shape; exposed package-private for tests. */
    record StatusReport(
            String model,
            String permission_mode,
            List<String> allowed_tools,
            Optional<String> session_id,
            int message_count,
            long input_tokens,
            long output_tokens) {
        StatusReport {
            allowed_tools = List.copyOf(allowed_tools);
            session_id = session_id == null ? Optional.empty() : session_id;
        }
    }
}
