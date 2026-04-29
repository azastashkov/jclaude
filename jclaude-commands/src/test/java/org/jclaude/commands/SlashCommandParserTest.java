package org.jclaude.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SlashCommandParserTest {

    private static String parse_error_message(String input) {
        try {
            SlashCommand.parse(input);
        } catch (SlashCommandParseError error) {
            return error.getMessage();
        }
        throw new AssertionError("expected SlashCommandParseError for: " + input);
    }

    @Test
    void parses_supported_slash_commands() {
        assertThat(SlashCommand.parse("/help")).contains(new SlashCommand.Help());
        assertThat(SlashCommand.parse(" /status ")).contains(new SlashCommand.Status());
        assertThat(SlashCommand.parse("/sandbox")).contains(new SlashCommand.Sandbox());
        assertThat(SlashCommand.parse("/bughunter runtime")).contains(new SlashCommand.Bughunter("runtime"));
        assertThat(SlashCommand.parse("/commit")).contains(new SlashCommand.Commit());
        assertThat(SlashCommand.parse("/pr ready for review")).contains(new SlashCommand.Pr("ready for review"));
        assertThat(SlashCommand.parse("/issue flaky test")).contains(new SlashCommand.Issue("flaky test"));
        assertThat(SlashCommand.parse("/ultraplan ship both features"))
                .contains(new SlashCommand.Ultraplan("ship both features"));
        assertThat(SlashCommand.parse("/teleport conversation.rs"))
                .contains(new SlashCommand.Teleport("conversation.rs"));
        assertThat(SlashCommand.parse("/debug-tool-call")).contains(new SlashCommand.DebugToolCall());
        assertThat(SlashCommand.parse("/model claude-opus")).contains(new SlashCommand.Model("claude-opus"));
        assertThat(SlashCommand.parse("/model")).contains(new SlashCommand.Model(null));
        assertThat(SlashCommand.parse("/permissions read-only")).contains(new SlashCommand.Permissions("read-only"));
        assertThat(SlashCommand.parse("/clear")).contains(new SlashCommand.Clear(false));
        assertThat(SlashCommand.parse("/clear --confirm")).contains(new SlashCommand.Clear(true));
        assertThat(SlashCommand.parse("/cost")).contains(new SlashCommand.Cost());
        assertThat(SlashCommand.parse("/resume session.json")).contains(new SlashCommand.Resume("session.json"));
        assertThat(SlashCommand.parse("/config")).contains(new SlashCommand.Config(null));
        assertThat(SlashCommand.parse("/config env")).contains(new SlashCommand.Config("env"));
        assertThat(SlashCommand.parse("/mcp")).contains(new SlashCommand.Mcp(null, null));
        assertThat(SlashCommand.parse("/mcp show remote")).contains(new SlashCommand.Mcp("show", "remote"));
        assertThat(SlashCommand.parse("/memory")).contains(new SlashCommand.Memory());
        assertThat(SlashCommand.parse("/init")).contains(new SlashCommand.Init());
        assertThat(SlashCommand.parse("/diff")).contains(new SlashCommand.Diff());
        assertThat(SlashCommand.parse("/version")).contains(new SlashCommand.Version());
        assertThat(SlashCommand.parse("/export notes.txt")).contains(new SlashCommand.Export("notes.txt"));
        assertThat(SlashCommand.parse("/session switch abc123")).contains(new SlashCommand.Session("switch", "abc123"));
        assertThat(SlashCommand.parse("/plugins install demo")).contains(new SlashCommand.Plugins("install", "demo"));
        assertThat(SlashCommand.parse("/plugins list")).contains(new SlashCommand.Plugins("list", null));
        assertThat(SlashCommand.parse("/plugins enable demo")).contains(new SlashCommand.Plugins("enable", "demo"));
        assertThat(SlashCommand.parse("/skills install ./fixtures/help-skill"))
                .contains(new SlashCommand.Skills("install ./fixtures/help-skill"));
        assertThat(SlashCommand.parse("/plugins disable demo")).contains(new SlashCommand.Plugins("disable", "demo"));
        assertThat(SlashCommand.parse("/session fork incident-review"))
                .contains(new SlashCommand.Session("fork", "incident-review"));
    }

    @Test
    void parses_history_command_without_count() {
        Optional<SlashCommand> parsed = SlashCommand.parse("/history");
        assertThat(parsed).contains(new SlashCommand.History(null));
    }

    @Test
    void parses_history_command_with_numeric_count() {
        Optional<SlashCommand> parsed = SlashCommand.parse("/history 25");
        assertThat(parsed).contains(new SlashCommand.History("25"));
    }

    @Test
    void rejects_history_with_extra_arguments() {
        String error = parse_error_message("/history 25 extra");
        assertThat(error).contains("Usage: /history [count]");
    }

    @Test
    void rejects_unexpected_arguments_for_no_arg_commands() {
        String error = parse_error_message("/compact now");
        assertThat(error).contains("Unexpected arguments for /compact.");
        assertThat(error).contains("  Usage            /compact");
        assertThat(error).contains("  Summary          Compact local session history");
    }

    @Test
    void rejects_invalid_argument_values() {
        String error = parse_error_message("/permissions admin");
        assertThat(error)
                .contains(
                        "Unsupported /permissions mode 'admin'. Use read-only, workspace-write, or danger-full-access.");
        assertThat(error).contains("  Usage            /permissions [read-only|workspace-write|danger-full-access]");
    }

    @Test
    void rejects_missing_required_arguments() {
        String error = parse_error_message("/teleport");
        assertThat(error).contains("Usage: /teleport <symbol-or-path>");
        assertThat(error).contains("  Category         Tools");
    }

    @Test
    void rejects_invalid_session_and_plugin_shapes() {
        String session_error = parse_error_message("/session switch");
        String plugin_error = parse_error_message("/plugins list extra");

        assertThat(session_error).contains("Usage: /session switch <session-id>");
        assertThat(session_error).contains("/session");
        assertThat(plugin_error).contains("Usage: /plugin list");
        assertThat(plugin_error).contains("Aliases          /plugins, /marketplace");
    }

    @Test
    void rejects_invalid_agents_arguments() {
        String error = parse_error_message("/agents show planner");
        assertThat(error)
                .contains(
                        "Unexpected arguments for /agents: show planner. Use /agents, /agents list, or /agents help.");
        assertThat(error).contains("  Usage            /agents [list|help]");
    }

    @Test
    void accepts_skills_invocation_arguments_for_prompt_dispatch() {
        assertThat(SlashCommand.parse("/skills help overview")).contains(new SlashCommand.Skills("help overview"));
        assertThat(SkillsHandler.classify_skills_slash_command("help overview"))
                .isEqualTo(new SkillSlashDispatch.Invoke("$help overview"));
        assertThat(SkillsHandler.classify_skills_slash_command("/test"))
                .isEqualTo(new SkillSlashDispatch.Invoke("$test"));
        assertThat(SkillsHandler.classify_skills_slash_command("install ./skill-pack"))
                .isEqualTo(new SkillSlashDispatch.Local());
    }

    @Test
    void rejects_invalid_mcp_arguments() {
        String show_error = parse_error_message("/mcp show alpha beta");
        assertThat(show_error).contains("Unexpected arguments for /mcp show.");
        assertThat(show_error).contains("  Usage            /mcp show <server>");

        String action_error = parse_error_message("/mcp inspect alpha");
        assertThat(action_error).contains("Unknown /mcp action 'inspect'. Use list, show <server>, or help.");
        assertThat(action_error).contains("  Usage            /mcp [list|show <server>|help]");
    }

    @Test
    void removed_login_and_logout_commands_report_env_auth_guidance() {
        assertThat(parse_error_message("/login")).contains("ANTHROPIC_API_KEY");
        assertThat(parse_error_message("/logout")).contains("ANTHROPIC_AUTH_TOKEN");
    }

    @Test
    void validate_slash_command_input_rejects_extra_single_value_arguments() {
        String session_error = parse_error_message("/session switch current next");
        String plugin_error = parse_error_message("/plugin enable demo extra");

        assertThat(session_error).contains("Unexpected arguments for /session switch.");
        assertThat(session_error).contains("  Usage            /session switch <session-id>");
        assertThat(plugin_error).contains("Unexpected arguments for /plugin enable.");
        assertThat(plugin_error).contains("  Usage            /plugin enable <name>");
    }
}
