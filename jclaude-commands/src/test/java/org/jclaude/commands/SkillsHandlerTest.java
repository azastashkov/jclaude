package org.jclaude.commands;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillsHandlerTest {

    private static void write_skill(Path root, String name, String description) throws IOException {
        Path skill_root = root.resolve(name);
        Files.createDirectories(skill_root);
        Files.writeString(
                skill_root.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n\n# " + name + "\n");
    }

    private static void write_legacy_command(Path root, String name, String description) throws IOException {
        Files.createDirectories(root);
        Files.writeString(
                root.resolve(name + ".md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n\n# " + name + "\n");
    }

    @Test
    void lists_skills_from_project_and_user_roots(@TempDir Path workspace, @TempDir Path user_home) throws IOException {
        Path project_skills = workspace.resolve(".codex").resolve("skills");
        Path project_commands = workspace.resolve(".claude").resolve("commands");
        Path user_skills = user_home.resolve(".codex").resolve("skills");

        write_skill(project_skills, "plan", "Project planning guidance");
        write_legacy_command(project_commands, "deploy", "Legacy deployment guidance");
        write_skill(user_skills, "plan", "User planning guidance");
        write_skill(user_skills, "help", "Help guidance");

        List<SkillRoot> roots = List.of(
                new SkillRoot(DefinitionSource.PROJECT_CODEX, project_skills, SkillOrigin.SKILLS_DIR),
                new SkillRoot(DefinitionSource.PROJECT_CLAUDE, project_commands, SkillOrigin.LEGACY_COMMANDS_DIR),
                new SkillRoot(DefinitionSource.USER_CODEX, user_skills, SkillOrigin.SKILLS_DIR));
        String report = SkillsHandler.render_skills_report(SkillLoader.load_skills_from_roots(roots));

        assertThat(report).contains("Skills");
        assertThat(report).contains("3 available skills");
        assertThat(report).contains("Project roots:");
        assertThat(report).contains("plan · Project planning guidance");
        assertThat(report).contains("deploy · Legacy deployment guidance · legacy /commands");
        assertThat(report).contains("User home roots:");
        assertThat(report).contains("(shadowed by Project roots) plan · User planning guidance");
        assertThat(report).contains("help · Help guidance");
    }

    @Test
    void resolves_project_skills_and_legacy_commands_from_shared_registry(@TempDir Path workspace) throws IOException {
        Path project_skills = workspace.resolve(".claw").resolve("skills");
        Path legacy_commands = workspace.resolve(".claw").resolve("commands");
        write_skill(project_skills, "plan", "Project planning guidance");
        write_legacy_command(legacy_commands, "handoff", "Legacy handoff guidance");

        assertThat(SkillsHandler.resolve_skill_path(workspace, "$plan"))
                .isEqualTo(project_skills.resolve("plan").resolve("SKILL.md"));
        assertThat(SkillsHandler.resolve_skill_path(workspace, "/handoff"))
                .isEqualTo(legacy_commands.resolve("handoff.md"));
    }

    @Test
    void renders_skills_reports_as_json(@TempDir Path workspace, @TempDir Path user_home) throws IOException {
        Path project_skills = workspace.resolve(".codex").resolve("skills");
        Path project_commands = workspace.resolve(".claude").resolve("commands");
        Path user_skills = user_home.resolve(".codex").resolve("skills");

        write_skill(project_skills, "plan", "Project planning guidance");
        write_legacy_command(project_commands, "deploy", "Legacy deployment guidance");
        write_skill(user_skills, "plan", "User planning guidance");
        write_skill(user_skills, "help", "Help guidance");

        List<SkillRoot> roots = List.of(
                new SkillRoot(DefinitionSource.PROJECT_CODEX, project_skills, SkillOrigin.SKILLS_DIR),
                new SkillRoot(DefinitionSource.PROJECT_CLAUDE, project_commands, SkillOrigin.LEGACY_COMMANDS_DIR),
                new SkillRoot(DefinitionSource.USER_CODEX, user_skills, SkillOrigin.SKILLS_DIR));
        JsonNode report = SkillsHandler.render_skills_report_json(SkillLoader.load_skills_from_roots(roots));

        assertThat(report.get("kind").asText()).isEqualTo("skills");
        assertThat(report.get("action").asText()).isEqualTo("list");
        assertThat(report.get("summary").get("active").asInt()).isEqualTo(3);
        assertThat(report.get("summary").get("shadowed").asInt()).isEqualTo(1);
        assertThat(report.get("skills").get(0).get("name").asText()).isEqualTo("plan");
        assertThat(report.get("skills").get(0).get("source").get("id").asText()).isEqualTo("project_claw");
        assertThat(report.get("skills").get(1).get("name").asText()).isEqualTo("deploy");
        assertThat(report.get("skills").get(1).get("origin").get("id").asText()).isEqualTo("legacy_commands_dir");
        assertThat(report.get("skills").get(3).get("shadowed_by").get("id").asText())
                .isEqualTo("project_claw");

        JsonNode help = SkillsHandler.handle_skills_slash_command_json("help", workspace);
        assertThat(help.get("kind").asText()).isEqualTo("skills");
        assertThat(help.get("action").asText()).isEqualTo("help");
        assertThat(help.get("usage").get("aliases").get(0).asText()).isEqualTo("/skill");
        assertThat(help.get("usage").get("direct_cli").asText())
                .isEqualTo("claw skills [list|install <path>|help|<skill> [args]]");
    }

    @Test
    void agents_and_skills_usage_support_help_and_unexpected_args(@TempDir Path cwd) throws IOException {
        String agents_help = AgentsHandler.handle_agents_slash_command("help", cwd);
        assertThat(agents_help).contains("Usage            /agents [list|help]");
        assertThat(agents_help).contains("Direct CLI       claw agents");
        assertThat(agents_help).contains("Sources          .claw/agents, ~/.claw/agents, $CLAW_CONFIG_HOME/agents");

        String agents_unexpected = AgentsHandler.handle_agents_slash_command("show planner", cwd);
        assertThat(agents_unexpected).contains("Unexpected       show planner");

        String skills_help = SkillsHandler.handle_skills_slash_command("--help", cwd);
        assertThat(skills_help).contains("Usage            /skills [list|install <path>|help|<skill> [args]]");
        assertThat(skills_help).contains("Alias            /skill");
        assertThat(skills_help).contains("Invoke           /skills help overview -> $help overview");
        assertThat(skills_help).contains("Install root     $CLAW_CONFIG_HOME/skills or ~/.claw/skills");
        assertThat(skills_help).contains(".omc/skills");
        assertThat(skills_help).contains(".agents/skills");
        assertThat(skills_help).contains("~/.claude/skills/omc-learned");
        assertThat(skills_help).contains("legacy /commands");

        String skills_unexpected = SkillsHandler.handle_skills_slash_command("show help", cwd);
        assertThat(skills_unexpected).contains("Unexpected       show");

        String skills_install_help = SkillsHandler.handle_skills_slash_command("install --help", cwd);
        assertThat(skills_install_help).contains("Usage            /skills [list|install <path>|help|<skill> [args]]");
        assertThat(skills_install_help).contains("Alias            /skill");
        assertThat(skills_install_help).contains("Unexpected       install");

        String skills_unknown_help = SkillsHandler.handle_skills_slash_command("show --help", cwd);
        assertThat(skills_unknown_help).contains("Usage            /skills [list|install <path>|help|<skill> [args]]");
        assertThat(skills_unknown_help).contains("Unexpected       show");

        JsonNode skills_help_json = SkillsHandler.handle_skills_slash_command_json("help", cwd);
        JsonNode sources = skills_help_json.get("usage").get("sources");
        assertThat(skills_help_json.get("usage").get("aliases").get(0).asText()).isEqualTo("/skill");
        boolean omc_found = false;
        boolean agents_found = false;
        boolean omc_home_found = false;
        boolean omc_learned_found = false;
        for (JsonNode src : sources) {
            String text = src.asText();
            if (text.equals(".omc/skills")) omc_found = true;
            if (text.equals(".agents/skills")) agents_found = true;
            if (text.equals("~/.omc/skills")) omc_home_found = true;
            if (text.equals("~/.claude/skills/omc-learned")) omc_learned_found = true;
        }
        assertThat(omc_found).isTrue();
        assertThat(agents_found).isTrue();
        assertThat(omc_home_found).isTrue();
        assertThat(omc_learned_found).isTrue();
    }

    @Test
    void parses_quoted_skill_frontmatter_values() {
        String contents = "---\nname: \"hud\"\ndescription: 'Quoted description'\n---\n";
        String[] meta = TomlMini.parse_skill_frontmatter(contents);
        assertThat(meta[0]).isEqualTo("hud");
        assertThat(meta[1]).isEqualTo("Quoted description");
    }

    @Test
    void discovers_omc_skills_from_project_and_user_compatibility_roots(
            @TempDir Path workspace, @TempDir Path user_home, @TempDir Path claude_config_dir) throws IOException {
        Path project_omc_skills = workspace.resolve(".omc").resolve("skills");
        Path project_agents_skills = workspace.resolve(".agents").resolve("skills");
        Path user_omc_skills = user_home.resolve(".omc").resolve("skills");
        Path claude_config_skills = claude_config_dir.resolve("skills");
        Path claude_config_commands = claude_config_dir.resolve("commands");
        Path learned_skills = claude_config_dir.resolve("skills").resolve("omc-learned");

        write_skill(project_omc_skills, "hud", "OMC HUD guidance");
        write_skill(project_agents_skills, "trace", "Compatibility skill guidance");
        write_skill(user_omc_skills, "cancel", "OMC cancel guidance");
        write_skill(claude_config_skills, "statusline", "Claude config skill guidance");
        write_legacy_command(claude_config_commands, "doctor-check", "Claude config command guidance");
        write_skill(learned_skills, "learned", "Learned skill guidance");

        // Java cannot mutate HOME/CLAUDE_CONFIG_DIR after process start, so we explicitly
        // build the same set of roots that DefinitionRoots.discover_skill_roots would produce.
        List<SkillRoot> roots = List.of(
                new SkillRoot(DefinitionSource.PROJECT_CLAW, project_omc_skills, SkillOrigin.SKILLS_DIR),
                new SkillRoot(DefinitionSource.PROJECT_CLAW, project_agents_skills, SkillOrigin.SKILLS_DIR),
                new SkillRoot(DefinitionSource.USER_CLAW, user_omc_skills, SkillOrigin.SKILLS_DIR),
                new SkillRoot(DefinitionSource.USER_CLAUDE, claude_config_skills, SkillOrigin.SKILLS_DIR),
                new SkillRoot(DefinitionSource.USER_CLAUDE, learned_skills, SkillOrigin.SKILLS_DIR),
                new SkillRoot(DefinitionSource.USER_CLAUDE, claude_config_commands, SkillOrigin.LEGACY_COMMANDS_DIR));
        String report = SkillsHandler.render_skills_report(SkillLoader.load_skills_from_roots(roots));

        assertThat(report).contains("available skills");
        assertThat(report).contains("hud · OMC HUD guidance");
        assertThat(report).contains("trace · Compatibility skill guidance");
        assertThat(report).contains("cancel · OMC cancel guidance");
        assertThat(report).contains("statusline · Claude config skill guidance");
        assertThat(report).contains("doctor-check · Claude config command guidance · legacy /commands");
        assertThat(report).contains("learned · Learned skill guidance");

        JsonNode help = SkillsHandler.handle_skills_slash_command_json("help", workspace);
        assertThat(help.get("usage").get("aliases").get(0).asText()).isEqualTo("/skill");
        JsonNode sources = help.get("usage").get("sources");
        boolean omc_found = false;
        boolean agents_found = false;
        boolean omc_home_found = false;
        boolean omc_learned_found = false;
        for (JsonNode src : sources) {
            String text = src.asText();
            if (text.equals(".omc/skills")) omc_found = true;
            if (text.equals(".agents/skills")) agents_found = true;
            if (text.equals("~/.omc/skills")) omc_home_found = true;
            if (text.equals("~/.claude/skills/omc-learned")) omc_learned_found = true;
        }
        assertThat(omc_found).isTrue();
        assertThat(agents_found).isTrue();
        assertThat(omc_home_found).isTrue();
        assertThat(omc_learned_found).isTrue();
    }
}
