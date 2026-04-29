package org.jclaude.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Subset of plugin.json's {@code tools[*]} fields the harness exposes to the model. The legacy
 * settings.json loader populates {@code command} and {@code required_permission}; the full
 * PluginManager-based loader leaves them {@code null} and uses the surrounding {@link PluginTool}
 * to carry that info.
 */
public record PluginToolDefinition(
        String name, String description, JsonNode input_schema, String command, String required_permission) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static PluginToolDefinition from_json(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String name = text_or_null(node.path("name"));
        String description = text_or_null(node.path("description"));
        String command = text_or_null(node.path("command"));
        String required_permission = text_or_null(node.path("requiredPermission"));
        if (name == null || command == null) {
            return null;
        }
        JsonNode input_schema_node = node.path("inputSchema");
        ObjectNode schema = input_schema_node.isObject() ? (ObjectNode) input_schema_node : MAPPER.createObjectNode();
        return new PluginToolDefinition(name, description, schema, command, required_permission);
    }

    /** Construct a definition for the rich PluginManager loader where command lives elsewhere. */
    public static PluginToolDefinition core(String name, String description, JsonNode input_schema) {
        return new PluginToolDefinition(name, description, input_schema, null, null);
    }

    private static String text_or_null(JsonNode value) {
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
