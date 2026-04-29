package org.jclaude.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

/**
 * Discovers the directory roots (project + user home + config-home) where
 * agents and skills are looked up. Mirrors the Rust {@code
 * discover_definition_roots} and {@code discover_skill_roots} helpers.
 */
public final class DefinitionRoots {

    private DefinitionRoots() {}

    /** Generic agent-or-similar definition discovery. */
    public static List<Entry<DefinitionSource, Path>> discover_definition_roots(Path cwd, String leaf) {
        List<Entry<DefinitionSource, Path>> roots = new ArrayList<>();

        for (Path ancestor : ancestors(cwd)) {
            push_unique_root(
                    roots,
                    DefinitionSource.PROJECT_CLAW,
                    ancestor.resolve(".claw").resolve(leaf));
            push_unique_root(
                    roots,
                    DefinitionSource.PROJECT_CODEX,
                    ancestor.resolve(".codex").resolve(leaf));
            push_unique_root(
                    roots,
                    DefinitionSource.PROJECT_CLAUDE,
                    ancestor.resolve(".claude").resolve(leaf));
        }

        String claw_config_home = System.getenv("CLAW_CONFIG_HOME");
        if (claw_config_home != null && !claw_config_home.isBlank()) {
            push_unique_root(
                    roots,
                    DefinitionSource.USER_CLAW_CONFIG_HOME,
                    Paths.get(claw_config_home).resolve(leaf));
        }
        String codex_home = System.getenv("CODEX_HOME");
        if (codex_home != null && !codex_home.isBlank()) {
            push_unique_root(
                    roots,
                    DefinitionSource.USER_CODEX_HOME,
                    Paths.get(codex_home).resolve(leaf));
        }
        String claude_config_dir = System.getenv("CLAUDE_CONFIG_DIR");
        if (claude_config_dir != null && !claude_config_dir.isBlank()) {
            push_unique_root(
                    roots,
                    DefinitionSource.USER_CLAUDE,
                    Paths.get(claude_config_dir).resolve(leaf));
        }
        String home = System.getenv("HOME");
        if (home != null && !home.isBlank()) {
            Path homePath = Paths.get(home);
            push_unique_root(
                    roots, DefinitionSource.USER_CLAW, homePath.resolve(".claw").resolve(leaf));
            push_unique_root(
                    roots,
                    DefinitionSource.USER_CODEX,
                    homePath.resolve(".codex").resolve(leaf));
            push_unique_root(
                    roots,
                    DefinitionSource.USER_CLAUDE,
                    homePath.resolve(".claude").resolve(leaf));
        }
        return roots;
    }

    /** Skill-specific discovery (covers .omc and .agents compatibility roots). */
    public static List<SkillRoot> discover_skill_roots(Path cwd) {
        List<SkillRoot> roots = new ArrayList<>();

        for (Path ancestor : ancestors(cwd)) {
            push_unique_skill_root(
                    roots,
                    DefinitionSource.PROJECT_CLAW,
                    ancestor.resolve(".claw").resolve("skills"),
                    SkillOrigin.SKILLS_DIR);
            push_unique_skill_root(
                    roots,
                    DefinitionSource.PROJECT_CLAW,
                    ancestor.resolve(".omc").resolve("skills"),
                    SkillOrigin.SKILLS_DIR);
            push_unique_skill_root(
                    roots,
                    DefinitionSource.PROJECT_CLAW,
                    ancestor.resolve(".agents").resolve("skills"),
                    SkillOrigin.SKILLS_DIR);
            push_unique_skill_root(
                    roots,
                    DefinitionSource.PROJECT_CODEX,
                    ancestor.resolve(".codex").resolve("skills"),
                    SkillOrigin.SKILLS_DIR);
            push_unique_skill_root(
                    roots,
                    DefinitionSource.PROJECT_CLAUDE,
                    ancestor.resolve(".claude").resolve("skills"),
                    SkillOrigin.SKILLS_DIR);
            push_unique_skill_root(
                    roots,
                    DefinitionSource.PROJECT_CLAW,
                    ancestor.resolve(".claw").resolve("commands"),
                    SkillOrigin.LEGACY_COMMANDS_DIR);
            push_unique_skill_root(
                    roots,
                    DefinitionSource.PROJECT_CODEX,
                    ancestor.resolve(".codex").resolve("commands"),
                    SkillOrigin.LEGACY_COMMANDS_DIR);
            push_unique_skill_root(
                    roots,
                    DefinitionSource.PROJECT_CLAUDE,
                    ancestor.resolve(".claude").resolve("commands"),
                    SkillOrigin.LEGACY_COMMANDS_DIR);
        }

        String claw_config_home = System.getenv("CLAW_CONFIG_HOME");
        if (claw_config_home != null && !claw_config_home.isBlank()) {
            Path base = Paths.get(claw_config_home);
            push_unique_skill_root(
                    roots, DefinitionSource.USER_CLAW_CONFIG_HOME, base.resolve("skills"), SkillOrigin.SKILLS_DIR);
            push_unique_skill_root(
                    roots,
                    DefinitionSource.USER_CLAW_CONFIG_HOME,
                    base.resolve("commands"),
                    SkillOrigin.LEGACY_COMMANDS_DIR);
        }
        String codex_home = System.getenv("CODEX_HOME");
        if (codex_home != null && !codex_home.isBlank()) {
            Path base = Paths.get(codex_home);
            push_unique_skill_root(
                    roots, DefinitionSource.USER_CODEX_HOME, base.resolve("skills"), SkillOrigin.SKILLS_DIR);
            push_unique_skill_root(
                    roots, DefinitionSource.USER_CODEX_HOME, base.resolve("commands"), SkillOrigin.LEGACY_COMMANDS_DIR);
        }

        String home = System.getenv("HOME");
        if (home != null && !home.isBlank()) {
            Path base = Paths.get(home);
            push_unique_skill_root(
                    roots, DefinitionSource.USER_CLAW, base.resolve(".claw").resolve("skills"), SkillOrigin.SKILLS_DIR);
            push_unique_skill_root(
                    roots, DefinitionSource.USER_CLAW, base.resolve(".omc").resolve("skills"), SkillOrigin.SKILLS_DIR);
            push_unique_skill_root(
                    roots,
                    DefinitionSource.USER_CLAW,
                    base.resolve(".claw").resolve("commands"),
                    SkillOrigin.LEGACY_COMMANDS_DIR);
            push_unique_skill_root(
                    roots,
                    DefinitionSource.USER_CODEX,
                    base.resolve(".codex").resolve("skills"),
                    SkillOrigin.SKILLS_DIR);
            push_unique_skill_root(
                    roots,
                    DefinitionSource.USER_CODEX,
                    base.resolve(".codex").resolve("commands"),
                    SkillOrigin.LEGACY_COMMANDS_DIR);
            push_unique_skill_root(
                    roots,
                    DefinitionSource.USER_CLAUDE,
                    base.resolve(".claude").resolve("skills"),
                    SkillOrigin.SKILLS_DIR);
            push_unique_skill_root(
                    roots,
                    DefinitionSource.USER_CLAUDE,
                    base.resolve(".claude").resolve("skills").resolve("omc-learned"),
                    SkillOrigin.SKILLS_DIR);
            push_unique_skill_root(
                    roots,
                    DefinitionSource.USER_CLAUDE,
                    base.resolve(".claude").resolve("commands"),
                    SkillOrigin.LEGACY_COMMANDS_DIR);
        }

        String claude_config_dir = System.getenv("CLAUDE_CONFIG_DIR");
        if (claude_config_dir != null && !claude_config_dir.isBlank()) {
            Path base = Paths.get(claude_config_dir);
            Path skills_dir = base.resolve("skills");
            push_unique_skill_root(roots, DefinitionSource.USER_CLAUDE, skills_dir, SkillOrigin.SKILLS_DIR);
            push_unique_skill_root(
                    roots, DefinitionSource.USER_CLAUDE, skills_dir.resolve("omc-learned"), SkillOrigin.SKILLS_DIR);
            push_unique_skill_root(
                    roots, DefinitionSource.USER_CLAUDE, base.resolve("commands"), SkillOrigin.LEGACY_COMMANDS_DIR);
        }
        return roots;
    }

    private static List<Path> ancestors(Path cwd) {
        List<Path> result = new ArrayList<>();
        Path current = cwd == null ? null : cwd.toAbsolutePath();
        while (current != null) {
            result.add(current);
            current = current.getParent();
        }
        return result;
    }

    private static void push_unique_root(
            List<Entry<DefinitionSource, Path>> roots, DefinitionSource source, Path path) {
        if (!Files.isDirectory(path)) {
            return;
        }
        for (Entry<DefinitionSource, Path> existing : roots) {
            if (existing.getValue().equals(path)) {
                return;
            }
        }
        roots.add(new SimpleEntry<>(source, path));
    }

    private static void push_unique_skill_root(
            List<SkillRoot> roots, DefinitionSource source, Path path, SkillOrigin origin) {
        if (!Files.isDirectory(path)) {
            return;
        }
        for (SkillRoot existing : roots) {
            if (existing.path().equals(path)) {
                return;
            }
        }
        roots.add(new SkillRoot(source, path, origin));
    }
}
