package org.jclaude.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

/** {@code /agents} handler. Mirrors the Rust {@code handle_agents_*} function pair. */
public final class AgentsHandler {

    private static final ObjectMapper JSON = CommandsJsonMappers.standard();

    private AgentsHandler() {}

    public static String handle_agents_slash_command(String args, Path cwd) throws IOException {
        String normalized = HandlerHelpers.normalize_optional_args(args);
        if (normalized != null) {
            List<String> help_path = HandlerHelpers.help_path_from_args(normalized);
            if (help_path != null) {
                return help_path.isEmpty()
                        ? render_agents_usage(null)
                        : render_agents_usage(String.join(" ", help_path));
            }
        }

        if (normalized == null || "list".equals(normalized)) {
            List<Entry<DefinitionSource, Path>> roots = DefinitionRoots.discover_definition_roots(cwd, "agents");
            return render_agents_report(AgentLoader.load_agents_from_roots(roots));
        }
        if (HandlerHelpers.is_help_arg(normalized)) {
            return render_agents_usage(null);
        }
        return render_agents_usage(normalized);
    }

    public static ObjectNode handle_agents_slash_command_json(String args, Path cwd) throws IOException {
        String normalized = HandlerHelpers.normalize_optional_args(args);
        if (normalized != null) {
            List<String> help_path = HandlerHelpers.help_path_from_args(normalized);
            if (help_path != null) {
                return help_path.isEmpty()
                        ? render_agents_usage_json(null)
                        : render_agents_usage_json(String.join(" ", help_path));
            }
        }

        if (normalized == null || "list".equals(normalized)) {
            List<Entry<DefinitionSource, Path>> roots = DefinitionRoots.discover_definition_roots(cwd, "agents");
            return render_agents_report_json(cwd, AgentLoader.load_agents_from_roots(roots));
        }
        if (HandlerHelpers.is_help_arg(normalized)) {
            return render_agents_usage_json(null);
        }
        return render_agents_usage_json(normalized);
    }

    static String render_agents_report(List<AgentSummary> agents) {
        if (agents.isEmpty()) {
            return "No agents found.";
        }
        long active = agents.stream().filter(a -> a.shadowed_by() == null).count();
        List<String> lines = new ArrayList<>();
        lines.add("Agents");
        lines.add("  " + active + " active agents");
        lines.add("");

        for (DefinitionScope scope :
                List.of(DefinitionScope.PROJECT, DefinitionScope.USER_CONFIG_HOME, DefinitionScope.USER_HOME)) {
            List<AgentSummary> group = agents.stream()
                    .filter(a -> a.source().report_scope() == scope)
                    .toList();
            if (group.isEmpty()) {
                continue;
            }
            lines.add(scope.label() + ":");
            for (AgentSummary agent : group) {
                String detail = agent_detail(agent);
                if (agent.shadowed_by() != null) {
                    lines.add("  (shadowed by " + agent.shadowed_by().label() + ") " + detail);
                } else {
                    lines.add("  " + detail);
                }
            }
            lines.add("");
        }
        String joined = String.join("\n", lines);
        return strip_trailing_whitespace(joined);
    }

    static ObjectNode render_agents_report_json(Path cwd, List<AgentSummary> agents) {
        long active = agents.stream().filter(a -> a.shadowed_by() == null).count();
        ObjectNode root = JSON.createObjectNode();
        root.put("kind", "agents");
        root.put("action", "list");
        root.put("working_directory", cwd.toString());
        root.put("count", agents.size());
        ObjectNode summary = root.putObject("summary");
        summary.put("total", agents.size());
        summary.put("active", active);
        summary.put("shadowed", Math.max(0, agents.size() - active));
        ArrayNode arr = root.putArray("agents");
        for (AgentSummary agent : agents) {
            arr.add(agent_summary_json(agent));
        }
        return root;
    }

    private static ObjectNode agent_summary_json(AgentSummary agent) {
        ObjectNode node = JSON.createObjectNode();
        node.put("name", agent.name());
        if (agent.description() != null) {
            node.put("description", agent.description());
        } else {
            node.putNull("description");
        }
        if (agent.model() != null) {
            node.put("model", agent.model());
        } else {
            node.putNull("model");
        }
        if (agent.reasoning_effort() != null) {
            node.put("reasoning_effort", agent.reasoning_effort());
        } else {
            node.putNull("reasoning_effort");
        }
        node.set("source", definition_source_json(agent.source()));
        node.put("active", agent.shadowed_by() == null);
        if (agent.shadowed_by() != null) {
            node.set("shadowed_by", definition_source_json(agent.shadowed_by()));
        } else {
            node.putNull("shadowed_by");
        }
        return node;
    }

    static ObjectNode definition_source_json(DefinitionSource source) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", source.json_id());
        node.put("label", source.label());
        return node;
    }

    private static String agent_detail(AgentSummary agent) {
        List<String> parts = new ArrayList<>();
        parts.add(agent.name());
        if (agent.description() != null) {
            parts.add(agent.description());
        }
        if (agent.model() != null) {
            parts.add(agent.model());
        }
        if (agent.reasoning_effort() != null) {
            parts.add(agent.reasoning_effort());
        }
        return String.join(" · ", parts);
    }

    static String render_agents_usage(String unexpected) {
        List<String> lines = new ArrayList<>();
        lines.add("Agents");
        lines.add("  Usage            /agents [list|help]");
        lines.add("  Direct CLI       claw agents");
        lines.add("  Sources          .claw/agents, ~/.claw/agents, $CLAW_CONFIG_HOME/agents");
        if (unexpected != null) {
            lines.add("  Unexpected       " + unexpected);
        }
        return String.join("\n", lines);
    }

    static ObjectNode render_agents_usage_json(String unexpected) {
        ObjectNode root = JSON.createObjectNode();
        root.put("kind", "agents");
        root.put("action", "help");
        ObjectNode usage = root.putObject("usage");
        usage.put("slash_command", "/agents [list|help]");
        usage.put("direct_cli", "claw agents [list|help]");
        ArrayNode sources = usage.putArray("sources");
        sources.add(".claw/agents");
        sources.add("~/.claw/agents");
        sources.add("$CLAW_CONFIG_HOME/agents");
        if (unexpected != null) {
            root.put("unexpected", unexpected);
        } else {
            root.putNull("unexpected");
        }
        return root;
    }

    private static String strip_trailing_whitespace(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static List<Entry<DefinitionSource, Path>> entry_list(DefinitionSource s, Path p) {
        return List.of(new SimpleEntry<>(s, p));
    }
}
