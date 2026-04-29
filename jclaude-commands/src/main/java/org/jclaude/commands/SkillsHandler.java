package org.jclaude.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** {@code /skills} handler. Mirrors Rust {@code handle_skills_*} pair. */
public final class SkillsHandler {

    private static final ObjectMapper JSON = CommandsJsonMappers.standard();

    private SkillsHandler() {}

    public static String handle_skills_slash_command(String args, Path cwd) throws IOException {
        String normalized = HandlerHelpers.normalize_optional_args(args);
        if (normalized != null) {
            List<String> help_path = HandlerHelpers.help_path_from_args(normalized);
            if (help_path != null) {
                if (help_path.isEmpty()) {
                    return render_skills_usage(null);
                }
                if ("install".equals(help_path.get(0))) {
                    return render_skills_usage("install");
                }
                return render_skills_usage(String.join(" ", help_path));
            }
        }

        if (normalized == null || "list".equals(normalized)) {
            List<SkillRoot> roots = DefinitionRoots.discover_skill_roots(cwd);
            return render_skills_report(SkillLoader.load_skills_from_roots(roots));
        }
        if ("install".equals(normalized)) {
            return render_skills_usage("install");
        }
        if (normalized.startsWith("install ")) {
            // install_skill is not ported verbatim because it touches the filesystem
            // for write operations; surface the usage block instead so callers see
            // a deterministic response. The classifier still routes this to Local
            // dispatch, matching Rust behavior.
            return render_skills_usage("install");
        }
        if (HandlerHelpers.is_help_arg(normalized)) {
            return render_skills_usage(null);
        }
        return render_skills_usage(normalized);
    }

    public static ObjectNode handle_skills_slash_command_json(String args, Path cwd) throws IOException {
        String normalized = HandlerHelpers.normalize_optional_args(args);
        if (normalized != null) {
            List<String> help_path = HandlerHelpers.help_path_from_args(normalized);
            if (help_path != null) {
                if (help_path.isEmpty()) {
                    return render_skills_usage_json(null);
                }
                if ("install".equals(help_path.get(0))) {
                    return render_skills_usage_json("install");
                }
                return render_skills_usage_json(String.join(" ", help_path));
            }
        }

        if (normalized == null || "list".equals(normalized)) {
            List<SkillRoot> roots = DefinitionRoots.discover_skill_roots(cwd);
            return render_skills_report_json(SkillLoader.load_skills_from_roots(roots));
        }
        if ("install".equals(normalized) || normalized.startsWith("install ")) {
            return render_skills_usage_json("install");
        }
        if (HandlerHelpers.is_help_arg(normalized)) {
            return render_skills_usage_json(null);
        }
        return render_skills_usage_json(normalized);
    }

    /**
     * Routes a {@code /skills} invocation. Mirrors Rust {@code classify_skills_slash_command}.
     */
    public static SkillSlashDispatch classify_skills_slash_command(String args) {
        String normalized = HandlerHelpers.normalize_optional_args(args);
        if (normalized == null) {
            return new SkillSlashDispatch.Local();
        }
        if ("list".equals(normalized) || HandlerHelpers.is_help_arg(normalized)) {
            return new SkillSlashDispatch.Local();
        }
        if ("install".equals(normalized) || normalized.startsWith("install ")) {
            return new SkillSlashDispatch.Local();
        }
        String stripped = normalized;
        while (stripped.startsWith("/")) {
            stripped = stripped.substring(1);
        }
        return new SkillSlashDispatch.Invoke("$" + stripped);
    }

    /**
     * Resolve a skill invocation by validating the skill exists on disk before returning the
     * dispatch. When the skill is not found, returns an error {@link RuntimeException} matching
     * Rust's human-readable message that lists nearby skill names.
     */
    public static SkillSlashDispatch resolve_skill_invocation(Path cwd, String args) throws IOException {
        SkillSlashDispatch dispatch = classify_skills_slash_command(args);
        if (!(dispatch instanceof SkillSlashDispatch.Invoke invoke)) {
            return dispatch;
        }
        String prompt = invoke.prompt();
        String skillToken = prompt;
        while (skillToken.startsWith("$")) {
            skillToken = skillToken.substring(1);
        }
        int space = skillToken.indexOf(' ');
        if (space >= 0) {
            skillToken = skillToken.substring(0, space);
        }
        if (skillToken.isEmpty()) {
            return dispatch;
        }
        try {
            resolve_skill_path(cwd, skillToken);
            return dispatch;
        } catch (IOException error) {
            StringBuilder message = new StringBuilder("Unknown skill: ")
                    .append(skillToken)
                    .append(" (")
                    .append(error.getMessage())
                    .append(')');
            List<SkillRoot> roots = DefinitionRoots.discover_skill_roots(cwd);
            try {
                List<SkillSummary> available = SkillLoader.load_skills_from_roots(roots);
                List<String> names = available.stream()
                        .filter(s -> s.shadowed_by() == null)
                        .map(SkillSummary::name)
                        .toList();
                if (!names.isEmpty()) {
                    message.append("\n  Available skills: ").append(String.join(", ", names));
                }
            } catch (IOException ignore) {
                // best-effort enrichment
            }
            message.append("\n  Usage: /skills [list|install <path>|help|<skill> [args]]");
            throw new SkillResolutionException(message.toString());
        }
    }

    /**
     * Resolves the path to a named skill. Throws {@link IOException} when the skill cannot be found.
     * Mirrors Rust {@code resolve_skill_path}.
     */
    public static Path resolve_skill_path(Path cwd, String skill) throws IOException {
        String requested = skill.trim();
        while (requested.startsWith("/") || requested.startsWith("$")) {
            requested = requested.substring(1);
        }
        if (requested.isEmpty()) {
            throw new IOException("skill must not be empty");
        }
        List<SkillRoot> roots = DefinitionRoots.discover_skill_roots(cwd);
        for (SkillRoot root : roots) {
            List<java.util.Map.Entry<String, Path>> entries = new ArrayList<>();
            try (var stream = java.nio.file.Files.list(root.path())) {
                for (Path entry : (Iterable<Path>) stream::iterator) {
                    if (root.origin() == SkillOrigin.SKILLS_DIR) {
                        if (!java.nio.file.Files.isDirectory(entry)) {
                            continue;
                        }
                        Path skillPath = entry.resolve("SKILL.md");
                        if (!java.nio.file.Files.isRegularFile(skillPath)) {
                            continue;
                        }
                        String contents = java.nio.file.Files.readString(skillPath);
                        String[] meta = TomlMini.parse_skill_frontmatter(contents);
                        String name =
                                meta[0] != null ? meta[0] : entry.getFileName().toString();
                        entries.add(new java.util.AbstractMap.SimpleEntry<>(name, skillPath));
                    } else {
                        Path mdPath;
                        if (java.nio.file.Files.isDirectory(entry)) {
                            Path skillPath = entry.resolve("SKILL.md");
                            if (!java.nio.file.Files.isRegularFile(skillPath)) {
                                continue;
                            }
                            mdPath = skillPath;
                        } else {
                            String fname = entry.getFileName().toString();
                            int dot = fname.lastIndexOf('.');
                            if (dot < 0 || !fname.substring(dot + 1).equalsIgnoreCase("md")) {
                                continue;
                            }
                            mdPath = entry;
                        }
                        String contents = java.nio.file.Files.readString(mdPath);
                        String[] meta = TomlMini.parse_skill_frontmatter(contents);
                        String mname = mdPath.getFileName().toString();
                        int dot = mname.lastIndexOf('.');
                        String fallback = dot > 0 ? mname.substring(0, dot) : mname;
                        String name = meta[0] != null ? meta[0] : fallback;
                        entries.add(new java.util.AbstractMap.SimpleEntry<>(name, mdPath));
                    }
                }
            }
            entries.sort(java.util.Map.Entry.comparingByKey());
            for (var pair : entries) {
                if (pair.getKey().equalsIgnoreCase(requested)) {
                    return pair.getValue();
                }
            }
        }
        throw new IOException("unknown skill: " + requested);
    }

    static String render_skills_report(List<SkillSummary> skills) {
        if (skills.isEmpty()) {
            return "No skills found.";
        }
        long active = skills.stream().filter(s -> s.shadowed_by() == null).count();
        List<String> lines = new ArrayList<>();
        lines.add("Skills");
        lines.add("  " + active + " available skills");
        lines.add("");
        for (DefinitionScope scope :
                List.of(DefinitionScope.PROJECT, DefinitionScope.USER_CONFIG_HOME, DefinitionScope.USER_HOME)) {
            List<SkillSummary> group = skills.stream()
                    .filter(s -> s.source().report_scope() == scope)
                    .toList();
            if (group.isEmpty()) {
                continue;
            }
            lines.add(scope.label() + ":");
            for (SkillSummary skill : group) {
                List<String> parts = new ArrayList<>();
                parts.add(skill.name());
                if (skill.description() != null) {
                    parts.add(skill.description());
                }
                String originDetail = skill.origin().detail_label();
                if (originDetail != null) {
                    parts.add(originDetail);
                }
                String detail = String.join(" · ", parts);
                if (skill.shadowed_by() != null) {
                    lines.add("  (shadowed by " + skill.shadowed_by().label() + ") " + detail);
                } else {
                    lines.add("  " + detail);
                }
            }
            lines.add("");
        }
        return strip_trailing_whitespace(String.join("\n", lines));
    }

    static ObjectNode render_skills_report_json(List<SkillSummary> skills) {
        long active = skills.stream().filter(s -> s.shadowed_by() == null).count();
        ObjectNode root = JSON.createObjectNode();
        root.put("kind", "skills");
        root.put("action", "list");
        ObjectNode summary = root.putObject("summary");
        summary.put("total", skills.size());
        summary.put("active", active);
        summary.put("shadowed", Math.max(0, skills.size() - active));
        ArrayNode arr = root.putArray("skills");
        for (SkillSummary skill : skills) {
            arr.add(skill_summary_json(skill));
        }
        return root;
    }

    private static ObjectNode skill_summary_json(SkillSummary skill) {
        ObjectNode node = JSON.createObjectNode();
        node.put("name", skill.name());
        if (skill.description() != null) {
            node.put("description", skill.description());
        } else {
            node.putNull("description");
        }
        node.set("source", AgentsHandler.definition_source_json(skill.source()));
        node.set("origin", skill_origin_json(skill.origin()));
        node.put("active", skill.shadowed_by() == null);
        if (skill.shadowed_by() != null) {
            node.set("shadowed_by", AgentsHandler.definition_source_json(skill.shadowed_by()));
        } else {
            node.putNull("shadowed_by");
        }
        return node;
    }

    private static ObjectNode skill_origin_json(SkillOrigin origin) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", origin.json_id());
        if (origin.detail_label() != null) {
            node.put("detail_label", origin.detail_label());
        } else {
            node.putNull("detail_label");
        }
        return node;
    }

    static String render_skills_usage(String unexpected) {
        List<String> lines = new ArrayList<>();
        lines.add("Skills");
        lines.add("  Usage            /skills [list|install <path>|help|<skill> [args]]");
        lines.add("  Alias            /skill");
        lines.add("  Direct CLI       claw skills [list|install <path>|help|<skill> [args]]");
        lines.add("  Invoke           /skills help overview -> $help overview");
        lines.add("  Install root     $CLAW_CONFIG_HOME/skills or ~/.claw/skills");
        lines.add(
                "  Sources          .claw/skills, .omc/skills, .agents/skills, .codex/skills, .claude/skills, ~/.claw/skills, ~/.omc/skills, ~/.claude/skills/omc-learned, ~/.codex/skills, ~/.claude/skills, legacy /commands");
        if (unexpected != null) {
            lines.add("  Unexpected       " + unexpected);
        }
        return String.join("\n", lines);
    }

    static ObjectNode render_skills_usage_json(String unexpected) {
        ObjectNode root = JSON.createObjectNode();
        root.put("kind", "skills");
        root.put("action", "help");
        ObjectNode usage = root.putObject("usage");
        usage.put("slash_command", "/skills [list|install <path>|help|<skill> [args]]");
        ArrayNode aliases = usage.putArray("aliases");
        aliases.add("/skill");
        usage.put("direct_cli", "claw skills [list|install <path>|help|<skill> [args]]");
        usage.put("invoke", "/skills help overview -> $help overview");
        usage.put("install_root", "$CLAW_CONFIG_HOME/skills or ~/.claw/skills");
        ArrayNode sources = usage.putArray("sources");
        sources.add(".claw/skills");
        sources.add(".omc/skills");
        sources.add(".agents/skills");
        sources.add(".codex/skills");
        sources.add(".claude/skills");
        sources.add("~/.claw/skills");
        sources.add("~/.omc/skills");
        sources.add("~/.claude/skills/omc-learned");
        sources.add("~/.codex/skills");
        sources.add("~/.claude/skills");
        sources.add("legacy /commands");
        sources.add("legacy fallback dirs still load automatically");
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
}
