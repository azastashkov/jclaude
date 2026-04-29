package org.jclaude.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Loads skill definitions from a list of {@link SkillRoot}s. */
public final class SkillLoader {

    private SkillLoader() {}

    /** Mirrors Rust {@code load_skills_from_roots}. */
    public static List<SkillSummary> load_skills_from_roots(List<SkillRoot> roots) throws IOException {
        List<SkillSummary> skills = new ArrayList<>();
        TreeMap<String, DefinitionSource> active_sources = new TreeMap<>();

        for (SkillRoot root : roots) {
            List<SkillSummary> root_skills = new ArrayList<>();
            try (Stream<Path> stream = Files.list(root.path())) {
                List<Path> entries = stream.toList();
                for (Path entry : entries) {
                    SkillSummary summary;
                    if (root.origin() == SkillOrigin.SKILLS_DIR) {
                        if (!Files.isDirectory(entry)) {
                            continue;
                        }
                        Path skillFile = entry.resolve("SKILL.md");
                        if (!Files.isRegularFile(skillFile)) {
                            continue;
                        }
                        String contents = Files.readString(skillFile);
                        String[] meta = TomlMini.parse_skill_frontmatter(contents);
                        String fallback = entry.getFileName().toString();
                        summary = new SkillSummary(
                                meta[0] != null ? meta[0] : fallback, meta[1], root.source(), null, root.origin());
                    } else {
                        Path markdownPath;
                        if (Files.isDirectory(entry)) {
                            Path skillFile = entry.resolve("SKILL.md");
                            if (!Files.isRegularFile(skillFile)) {
                                continue;
                            }
                            markdownPath = skillFile;
                        } else {
                            String fileName = entry.getFileName().toString();
                            int dot = fileName.lastIndexOf('.');
                            if (dot < 0 || !fileName.substring(dot + 1).equalsIgnoreCase("md")) {
                                continue;
                            }
                            markdownPath = entry;
                        }
                        String contents = Files.readString(markdownPath);
                        String[] meta = TomlMini.parse_skill_frontmatter(contents);
                        String mkName = markdownPath.getFileName().toString();
                        int dot = mkName.lastIndexOf('.');
                        String fallback = dot > 0 ? mkName.substring(0, dot) : mkName;
                        summary = new SkillSummary(
                                meta[0] != null ? meta[0] : fallback, meta[1], root.source(), null, root.origin());
                    }
                    root_skills.add(summary);
                }
            }
            root_skills.sort(Comparator.comparing(SkillSummary::name));
            for (SkillSummary skill : root_skills) {
                String key = skill.name().toLowerCase(Locale.ROOT);
                DefinitionSource existing = active_sources.get(key);
                if (existing != null) {
                    skills.add(skill.with_shadowed_by(existing));
                } else {
                    active_sources.put(key, skill.source());
                    skills.add(skill);
                }
            }
        }
        return skills;
    }
}
