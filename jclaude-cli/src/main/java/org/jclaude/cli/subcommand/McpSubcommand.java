package org.jclaude.cli.subcommand;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.cli.OutputFormat;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code jclaude mcp} — list MCP servers configured in {@code ~/.jclaude/settings.json} along with
 * their declared transport and command/url. Mirrors the Rust {@code handle_mcp_command} list path.
 */
@Command(
        name = "mcp",
        mixinStandardHelpOptions = true,
        description = "List configured MCP servers and their transports.")
public final class McpSubcommand implements Callable<Integer> {

    @Option(
            names = {"--output-format"},
            description = "Output format: text or json (default: ${DEFAULT-VALUE}).",
            defaultValue = "text")
    private String output_format;

    @Override
    public Integer call() {
        OutputFormat fmt = OutputFormat.parse(output_format);
        try {
            List<McpServerEntry> servers = collect();
            if (fmt == OutputFormat.JSON) {
                System.out.println(format_mcp_json(servers));
            } else {
                System.out.println(format_mcp_text(servers));
            }
            return 0;
        } catch (IOException error) {
            System.err.println("error: " + error.getMessage());
            return 1;
        }
    }

    static List<McpServerEntry> collect() throws IOException {
        ObjectNode settings = ConfigSubcommand.read_settings();
        JsonNode mcp = settings.get("mcpServers");
        if (mcp == null || !mcp.isObject()) {
            return List.of();
        }
        Map<String, McpServerEntry> sorted = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = mcp.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> field = it.next();
            String name = field.getKey();
            JsonNode spec = field.getValue();
            sorted.put(name, parse_entry(name, spec));
        }
        return new ArrayList<>(sorted.values());
    }

    private static McpServerEntry parse_entry(String name, JsonNode spec) {
        if (spec == null || !spec.isObject()) {
            return new McpServerEntry(name, "unknown", null, null, "missing or invalid configuration");
        }
        String type = string_field(spec, "type");
        String command = string_field(spec, "command");
        String url = string_field(spec, "url");
        String transport;
        if (type != null) {
            transport = type;
        } else if (command != null) {
            transport = "stdio";
        } else if (url != null) {
            transport = "http";
        } else {
            transport = "unknown";
        }
        String status_note = (command == null && url == null) ? "missing command/url" : "configured";
        return new McpServerEntry(name, transport, command, url, status_note);
    }

    private static String string_field(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    static String format_mcp_text(List<McpServerEntry> servers) {
        if (servers.isEmpty()) {
            return "MCP\n  (no servers configured)\n  Tip: add an entry to mcpServers in ~/.jclaude/settings.json.";
        }
        List<String> lines = new ArrayList<>();
        lines.add("MCP");
        lines.add("  " + servers.size() + " server" + (servers.size() == 1 ? "" : "s") + " configured");
        lines.add("");
        for (McpServerEntry s : servers) {
            String detail = s.command() != null
                    ? "command=" + s.command()
                    : (s.url() != null ? "url=" + s.url() : "(no transport)");
            lines.add("  " + s.name() + "  [" + s.transport() + "]  " + detail + "  — " + s.status());
        }
        return String.join("\n", lines);
    }

    static String format_mcp_json(List<McpServerEntry> servers) {
        ObjectMapper mapper = JclaudeMappers.standard();
        ObjectNode root = mapper.createObjectNode();
        root.put("kind", "mcp");
        root.put("action", "list");
        root.put("count", servers.size());
        ArrayNode arr = root.putArray("servers");
        for (McpServerEntry s : servers) {
            ObjectNode entry = arr.addObject();
            entry.put("name", s.name());
            entry.put("transport", s.transport());
            if (s.command() != null) {
                entry.put("command", s.command());
            } else {
                entry.putNull("command");
            }
            if (s.url() != null) {
                entry.put("url", s.url());
            } else {
                entry.putNull("url");
            }
            entry.put("status", s.status());
        }
        return root.toPrettyString();
    }

    /** Internal record exposed package-private for tests. */
    record McpServerEntry(String name, String transport, String command, String url, String status) {}
}
