package org.jclaude.commands;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HelpRendererTest {

    @Test
    void renders_help_from_shared_specs() {
        String help = HelpRenderer.render_slash_command_help();
        assertThat(help).contains("Start here        /status, /diff, /agents, /skills, /commit");
        assertThat(help).contains("[resume]          also works with --resume SESSION.jsonl");
        assertThat(help).contains("Session");
        assertThat(help).contains("Tools");
        assertThat(help).contains("Config");
        assertThat(help).contains("Debug");
        assertThat(help).contains("/help");
        assertThat(help).contains("/status");
        assertThat(help).contains("/sandbox");
        assertThat(help).contains("/compact");
        assertThat(help).contains("/bughunter [scope]");
        assertThat(help).contains("/commit");
        assertThat(help).contains("/pr [context]");
        assertThat(help).contains("/issue [context]");
        assertThat(help).contains("/ultraplan [task]");
        assertThat(help).contains("/teleport <symbol-or-path>");
        assertThat(help).contains("/debug-tool-call");
        assertThat(help).contains("/model [model]");
        assertThat(help).contains("/permissions [read-only|workspace-write|danger-full-access]");
        assertThat(help).contains("/clear [--confirm]");
        assertThat(help).contains("/cost");
        assertThat(help).contains("/resume <session-path>");
        assertThat(help).contains("/config [env|hooks|model|plugins]");
        assertThat(help).contains("/mcp [list|show <server>|help]");
        assertThat(help).contains("/memory");
        assertThat(help).contains("/init");
        assertThat(help).contains("/diff");
        assertThat(help).contains("/version");
        assertThat(help).contains("/export [file]");
        assertThat(help).contains("/session");
        assertThat(help)
                .contains("/plugin [list|install <path>|enable <name>|disable <name>|uninstall <id>|update <id>]");
        assertThat(help).contains("aliases: /plugins, /marketplace");
        assertThat(help).contains("/agents [list|help]");
        assertThat(help).contains("/skills [list|install <path>|help|<skill> [args]]");
        assertThat(help).contains("aliases: /skill");
        assertThat(help).doesNotContain("/login");
        assertThat(help).doesNotContain("/logout");
        assertThat(SlashCommandSpecs.slash_command_specs()).hasSize(139);
        assertThat(SlashCommandSpecs.resume_supported_slash_commands().size()).isGreaterThanOrEqualTo(39);
    }

    @Test
    void renders_help_with_grouped_categories_and_keyboard_shortcuts() {
        String[] categories = {"Session", "Tools", "Config", "Debug"};
        String help = HelpRenderer.render_slash_command_help();
        for (String category : categories) {
            assertThat(help).contains(category);
        }
        int session_index = help.indexOf("Session");
        int tools_index = help.indexOf("Tools");
        int config_index = help.indexOf("Config");
        int debug_index = help.indexOf("Debug");
        assertThat(session_index).isLessThan(tools_index);
        assertThat(tools_index).isLessThan(config_index);
        assertThat(config_index).isLessThan(debug_index);

        assertThat(help).contains("Keyboard shortcuts");
        assertThat(help).contains("Up/Down              Navigate prompt history");
        assertThat(help).contains("Tab                  Complete commands, modes, and recent sessions");
        assertThat(help).contains("Ctrl-C               Clear input (or exit on empty prompt)");
        assertThat(help).contains("Shift+Enter/Ctrl+J   Insert a newline");

        for (SlashCommandSpec spec : SlashCommandSpecs.slash_command_specs()) {
            String usage =
                    spec.argument_hint() != null ? "/" + spec.name() + " " + spec.argument_hint() : "/" + spec.name();
            assertThat(help).contains(usage);
            assertThat(help).contains(spec.summary());
        }
    }

    @Test
    void renders_per_command_help_detail() {
        String help = HelpRenderer.render_slash_command_help_detail("plugins");
        assertThat(help).isNotNull();
        assertThat(help).contains("/plugin");
        assertThat(help).contains("Summary          Manage Claw Code plugins");
        assertThat(help).contains("Aliases          /plugins, /marketplace");
        assertThat(help).contains("Category         Tools");
    }

    @Test
    void renders_per_command_help_detail_for_mcp() {
        String help = HelpRenderer.render_slash_command_help_detail("mcp");
        assertThat(help).isNotNull();
        assertThat(help).contains("/mcp");
        assertThat(help).contains("Summary          Inspect configured MCP servers");
        assertThat(help).contains("Category         Tools");
        assertThat(help).contains("Resume           Supported with --resume SESSION.jsonl");
    }
}
