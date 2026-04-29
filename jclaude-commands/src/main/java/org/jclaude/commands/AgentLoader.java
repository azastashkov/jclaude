package org.jclaude.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Loads agent definitions from a list of {@code (source, root)} pairs. */
public final class AgentLoader {

    private AgentLoader() {}

    /** Mirrors Rust {@code load_agents_from_roots}. */
    public static List<AgentSummary> load_agents_from_roots(List<Entry<DefinitionSource, Path>> roots)
            throws IOException {
        List<AgentSummary> agents = new ArrayList<>();
        TreeMap<String, DefinitionSource> active_sources = new TreeMap<>();

        for (Entry<DefinitionSource, Path> entry : roots) {
            DefinitionSource source = entry.getKey();
            Path root = entry.getValue();
            List<AgentSummary> root_agents = new ArrayList<>();
            try (Stream<Path> stream = Files.list(root)) {
                List<Path> files = stream.toList();
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".toml")) {
                        continue;
                    }
                    String contents = Files.readString(file);
                    String fallback_name = name.endsWith(".toml") ? name.substring(0, name.length() - 5) : name;
                    String parsedName = TomlMini.parse_toml_string(contents, "name");
                    if (parsedName == null) {
                        parsedName = fallback_name;
                    }
                    root_agents.add(new AgentSummary(
                            parsedName,
                            TomlMini.parse_toml_string(contents, "description"),
                            TomlMini.parse_toml_string(contents, "model"),
                            TomlMini.parse_toml_string(contents, "model_reasoning_effort"),
                            source,
                            null));
                }
            }
            root_agents.sort(Comparator.comparing(AgentSummary::name));
            for (AgentSummary agent : root_agents) {
                String key = agent.name().toLowerCase(Locale.ROOT);
                DefinitionSource existing = active_sources.get(key);
                if (existing != null) {
                    agents.add(agent.with_shadowed_by(existing));
                } else {
                    active_sources.put(key, agent.source());
                    agents.add(agent);
                }
            }
        }
        return agents;
    }
}
