package org.jclaude.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class CompatHarnessTest {

    private static UpstreamPaths fixture_paths() {
        // Walk up from the gradle module up to the workspace and try to find an upstream checkout.
        Path module_dir = Paths.get(System.getProperty("user.dir"));
        return UpstreamPaths.from_workspace_dir(module_dir);
    }

    private static boolean has_upstream_fixture(UpstreamPaths paths) {
        return Files.isRegularFile(paths.commands_path())
                && Files.isRegularFile(paths.tools_path())
                && Files.isRegularFile(paths.cli_path());
    }

    @Test
    void extracts_non_empty_manifests_from_upstream_repo() throws Exception {
        UpstreamPaths paths = fixture_paths();
        if (!has_upstream_fixture(paths)) {
            return;
        }
        ExtractedManifest manifest = CompatHarness.extract_manifest(paths);
        assertThat(manifest.commands().entries()).isNotEmpty();
        assertThat(manifest.tools().entries()).isNotEmpty();
        assertThat(manifest.bootstrap().phases()).isNotEmpty();
    }

    @Test
    void detects_known_upstream_command_symbols() throws Exception {
        UpstreamPaths paths = fixture_paths();
        if (!Files.isRegularFile(paths.commands_path())) {
            return;
        }
        CommandRegistry commands = CompatHarness.extract_commands(Files.readString(paths.commands_path()));
        var names = commands.entries().stream().map(CommandManifestEntry::name).toList();
        assertThat(names).contains("addDir", "review");
        assertThat(names).doesNotContain("INTERNAL_ONLY_COMMANDS");
    }

    @Test
    void detects_known_upstream_tool_symbols() throws Exception {
        UpstreamPaths paths = fixture_paths();
        if (!Files.isRegularFile(paths.tools_path())) {
            return;
        }
        ToolRegistry tools = CompatHarness.extract_tools(Files.readString(paths.tools_path()));
        var names = tools.entries().stream().map(ToolManifestEntry::name).toList();
        assertThat(names).contains("AgentTool", "BashTool");
    }

    @Test
    void extract_commands_collects_internal_only_entries() {
        String source = "import { foo, bar } from './commands/foo';\n"
                + "export const INTERNAL_ONLY_COMMANDS = [\n"
                + "  internalOne,\n"
                + "  internalTwo,\n"
                + "];\n"
                + "feature_gated_command = feature('z') && './commands/z';\n";
        CommandRegistry registry = CompatHarness.extract_commands(source);
        var by_source =
                registry.entries().stream().map(CommandManifestEntry::source).toList();
        assertThat(by_source).contains(CommandSource.BUILTIN, CommandSource.INTERNAL_ONLY, CommandSource.FEATURE_GATED);
    }

    @Test
    void extract_tools_picks_up_base_and_conditional_tools() {
        String source = "import { AgentTool, BashTool } from './tools/agent';\n"
                + "ChromeTool = feature('chrome') && require('./tools/chrome');\n";
        ToolRegistry registry = CompatHarness.extract_tools(source);
        var sources = registry.entries().stream().map(ToolManifestEntry::source).toList();
        assertThat(sources).contains(ToolSource.BASE, ToolSource.CONDITIONAL);
    }

    @Test
    void extract_bootstrap_plan_emits_main_runtime_terminator() {
        BootstrapPlan plan = CompatHarness.extract_bootstrap_plan("");
        assertThat(plan.phases()).startsWith(BootstrapPhase.CLI_ENTRY).endsWith(BootstrapPhase.MAIN_RUNTIME);
    }

    @Test
    void extract_bootstrap_plan_detects_known_fast_paths() {
        String source =
                "args.includes('--version') && args.includes('--bg') && args[0] === 'daemon' && remote-control && environment-runner";
        BootstrapPlan plan = CompatHarness.extract_bootstrap_plan(source);
        assertThat(plan.phases())
                .contains(
                        BootstrapPhase.FAST_PATH_VERSION,
                        BootstrapPhase.BACKGROUND_SESSION_FAST_PATH,
                        BootstrapPhase.DAEMON_FAST_PATH,
                        BootstrapPhase.BRIDGE_FAST_PATH,
                        BootstrapPhase.ENVIRONMENT_RUNNER_FAST_PATH);
    }
}
