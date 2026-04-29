package org.jclaude.cli.subcommand;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.cli.OutputFormat;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code jclaude config get|set|list} — read/write {@code ~/.jclaude/settings.json}. The
 * directory is overrideable via {@code JCLAUDE_CONFIG_HOME}. Mirrors Rust {@code
 * handle_config_command} dispatch.
 */
@Command(
        name = "config",
        mixinStandardHelpOptions = true,
        description = "Read or write settings entries from ~/.jclaude/settings.json",
        subcommands = {
            ConfigSubcommand.GetCommand.class,
            ConfigSubcommand.SetCommand.class,
            ConfigSubcommand.ListCommand.class
        })
public final class ConfigSubcommand implements Callable<Integer> {

    @Override
    public Integer call() {
        // Default action: list.
        return new ListCommand().call();
    }

    static Path settings_path() {
        String override = System.getenv("JCLAUDE_CONFIG_HOME");
        if (override != null && !override.isBlank()) {
            return Paths.get(override).resolve("settings.json");
        }
        String home = System.getenv("HOME");
        if (home == null || home.isBlank()) {
            home = System.getProperty("user.home");
        }
        return Paths.get(home == null ? "." : home).resolve(".jclaude").resolve("settings.json");
    }

    static ObjectNode read_settings() throws IOException {
        ObjectMapper mapper = JclaudeMappers.standard();
        Path path = settings_path();
        if (!Files.exists(path)) {
            return mapper.createObjectNode();
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.trim().isEmpty()) {
            return mapper.createObjectNode();
        }
        JsonNode parsed = mapper.readTree(text);
        if (!parsed.isObject()) {
            throw new IOException("settings.json is not a JSON object: " + path);
        }
        return (ObjectNode) parsed;
    }

    static void write_settings(ObjectNode node) throws IOException {
        Path path = settings_path();
        Files.createDirectories(path.getParent());
        ObjectMapper mapper = JclaudeMappers.standard();
        Files.writeString(
                path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node), StandardCharsets.UTF_8);
    }

    @Command(name = "get", description = "Read a single settings entry by key")
    static final class GetCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Settings key (top-level field name).")
        private String key;

        @Option(
                names = {"--output-format"},
                description = "Output format: text or json (default: ${DEFAULT-VALUE}).",
                defaultValue = "text")
        private String output_format;

        @Override
        public Integer call() {
            OutputFormat fmt = OutputFormat.parse(output_format);
            try {
                ObjectNode root = read_settings();
                JsonNode value = root.get(key);
                if (fmt == OutputFormat.JSON) {
                    ObjectMapper mapper = JclaudeMappers.standard();
                    ObjectNode out = mapper.createObjectNode();
                    out.put("kind", "config");
                    out.put("action", "get");
                    out.put("key", key);
                    if (value == null) {
                        out.putNull("value");
                        out.put("present", false);
                    } else {
                        out.set("value", value);
                        out.put("present", true);
                    }
                    System.out.println(out.toPrettyString());
                } else {
                    if (value == null) {
                        System.out.println("(not set)");
                    } else if (value.isTextual()) {
                        System.out.println(value.asText());
                    } else {
                        System.out.println(value.toString());
                    }
                }
                return 0;
            } catch (IOException error) {
                System.err.println("error: " + error.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "set", description = "Write a single settings entry by key")
    static final class SetCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Settings key (top-level field name).")
        private String key;

        @Parameters(index = "1", description = "Settings value (JSON literal or plain string).")
        private String value;

        @Option(
                names = {"--output-format"},
                description = "Output format: text or json (default: ${DEFAULT-VALUE}).",
                defaultValue = "text")
        private String output_format;

        @Override
        public Integer call() {
            OutputFormat fmt = OutputFormat.parse(output_format);
            try {
                ObjectMapper mapper = JclaudeMappers.standard();
                ObjectNode root = read_settings();
                JsonNode parsed_value = parse_value(mapper, value);
                root.set(key, parsed_value);
                write_settings(root);

                if (fmt == OutputFormat.JSON) {
                    ObjectNode out = mapper.createObjectNode();
                    out.put("kind", "config");
                    out.put("action", "set");
                    out.put("key", key);
                    out.set("value", parsed_value);
                    out.put("path", settings_path().toString());
                    System.out.println(out.toPrettyString());
                } else {
                    System.out.println("set " + key + " = " + parsed_value.toString());
                }
                return 0;
            } catch (IOException error) {
                System.err.println("error: " + error.getMessage());
                return 1;
            }
        }

        private static JsonNode parse_value(ObjectMapper mapper, String raw) {
            try {
                return mapper.readTree(raw);
            } catch (JsonProcessingException ignored) {
                return mapper.getNodeFactory().textNode(raw);
            }
        }
    }

    @Command(name = "list", description = "List all settings entries")
    static final class ListCommand implements Callable<Integer> {

        @Option(
                names = {"--output-format"},
                description = "Output format: text or json (default: ${DEFAULT-VALUE}).",
                defaultValue = "text")
        private String output_format;

        @Override
        public Integer call() {
            OutputFormat fmt = OutputFormat.parse(output_format);
            try {
                ObjectNode root = read_settings();
                if (fmt == OutputFormat.JSON) {
                    ObjectMapper mapper = JclaudeMappers.standard();
                    ObjectNode out = mapper.createObjectNode();
                    out.put("kind", "config");
                    out.put("action", "list");
                    out.put("path", settings_path().toString());
                    out.put("exists", Files.exists(settings_path()));
                    out.set("entries", root);
                    System.out.println(out.toPrettyString());
                } else {
                    System.out.println("Config (" + settings_path() + ")");
                    if (root.isEmpty()) {
                        System.out.println("  (empty)");
                    } else {
                        Map<String, JsonNode> sorted = new LinkedHashMap<>();
                        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
                        while (it.hasNext()) {
                            Map.Entry<String, JsonNode> e = it.next();
                            sorted.put(e.getKey(), e.getValue());
                        }
                        for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                            JsonNode v = entry.getValue();
                            String rendered = v.isTextual() ? v.asText() : v.toString();
                            System.out.println("  " + entry.getKey() + " = " + rendered);
                        }
                    }
                }
                return 0;
            } catch (IOException error) {
                System.err.println("error: " + error.getMessage());
                return 1;
            }
        }
    }
}
