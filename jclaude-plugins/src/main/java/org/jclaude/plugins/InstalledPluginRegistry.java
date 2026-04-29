package org.jclaude.plugins;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Mutable map of plugin id -> {@link InstalledPluginRecord}, persisted to {@code installed.json}.
 * Mirrors the Rust {@code InstalledPluginRegistry} struct.
 */
public final class InstalledPluginRegistry {

    private final TreeMap<String, InstalledPluginRecord> plugins = new TreeMap<>();

    public void put(String id, InstalledPluginRecord record) {
        plugins.put(id, record);
    }

    public InstalledPluginRecord get(String id) {
        return plugins.get(id);
    }

    public InstalledPluginRecord remove(String id) {
        return plugins.remove(id);
    }

    public boolean containsKey(String id) {
        return plugins.containsKey(id);
    }

    public Collection<InstalledPluginRecord> values() {
        return plugins.values();
    }

    public Iterable<Map.Entry<String, InstalledPluginRecord>> entries() {
        return plugins.entrySet();
    }

    public Map<String, InstalledPluginRecord> as_map() {
        return new LinkedHashMap<>(plugins);
    }

    public List<PluginSummary> summaries() {
        List<PluginSummary> out = new ArrayList<>(plugins.size());
        for (InstalledPluginRecord r : plugins.values()) {
            PluginMetadata metadata = new PluginMetadata(
                    r.id(),
                    r.name(),
                    r.version(),
                    r.description(),
                    r.kind(),
                    r.source().describe(),
                    false,
                    java.util.Optional.of(r.install_path()));
            out.add(new PluginSummary(metadata, false));
        }
        return out;
    }

    public static InstalledPluginRegistry from_json(JsonNode root) {
        InstalledPluginRegistry registry = new InstalledPluginRegistry();
        if (!root.isObject()) {
            return registry;
        }
        JsonNode plugins_node = root.path("plugins");
        if (!plugins_node.isObject()) {
            return registry;
        }
        var iter = plugins_node.fields();
        while (iter.hasNext()) {
            Map.Entry<String, JsonNode> entry = iter.next();
            InstalledPluginRecord record = parse_record(entry.getValue());
            if (record != null) {
                registry.plugins.put(entry.getKey(), record);
            }
        }
        return registry;
    }

    private static InstalledPluginRecord parse_record(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        PluginKind kind = PluginKind.from_label(node.path("kind").asText("external"));
        String id = node.path("id").asText();
        String name = node.path("name").asText();
        String version = node.path("version").asText();
        String description = node.path("description").asText();
        Path install_path = Paths.get(node.path("install_path").asText());
        PluginInstallSource source = parse_source(node.path("source"));
        long installed_at = node.path("installed_at_unix_ms").asLong(0L);
        long updated_at = node.path("updated_at_unix_ms").asLong(0L);
        return new InstalledPluginRecord(
                kind, id, name, version, description, install_path, source, installed_at, updated_at);
    }

    private static PluginInstallSource parse_source(JsonNode node) {
        String type = node.path("type").asText("local_path");
        if ("git_url".equals(type)) {
            return new PluginInstallSource.GitUrl(node.path("url").asText());
        }
        return new PluginInstallSource.LocalPath(Paths.get(node.path("path").asText("")));
    }

    public String to_json_string(ObjectMapper mapper) throws JsonProcessingException {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode plugins_node = mapper.createObjectNode();
        for (Map.Entry<String, InstalledPluginRecord> entry : plugins.entrySet()) {
            plugins_node.set(entry.getKey(), to_record_node(entry.getValue(), mapper));
        }
        root.set("plugins", plugins_node);
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private static ObjectNode to_record_node(InstalledPluginRecord record, ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("kind", record.kind().marketplace());
        node.put("id", record.id());
        node.put("name", record.name());
        node.put("version", record.version());
        node.put("description", record.description());
        node.put("install_path", record.install_path().toString());
        node.set("source", to_source_node(record.source(), mapper));
        node.put("installed_at_unix_ms", record.installed_at_unix_ms());
        node.put("updated_at_unix_ms", record.updated_at_unix_ms());
        return node;
    }

    private static ObjectNode to_source_node(PluginInstallSource source, ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        switch (source) {
            case PluginInstallSource.LocalPath l -> {
                node.put("type", "local_path");
                node.put("path", l.path().toString());
            }
            case PluginInstallSource.GitUrl g -> {
                node.put("type", "git_url");
                node.put("url", g.url());
            }
        }
        return node;
    }

    /** Returns a JSON ArrayNode of plugin ids — convenience for tests. */
    public ArrayNode plugin_ids(ObjectMapper mapper) {
        ArrayNode node = mapper.createArrayNode();
        for (String id : plugins.keySet()) {
            node.add(id);
        }
        return node;
    }
}
