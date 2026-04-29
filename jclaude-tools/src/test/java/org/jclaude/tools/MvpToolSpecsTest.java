package org.jclaude.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.jclaude.runtime.mcp.McpToolBridge;
import org.jclaude.tools.bridge.McpToolBridgeAdapter;
import org.junit.jupiter.api.Test;

class MvpToolSpecsTest {

    @Test
    void mvp_tool_specs_lists_thirteen_tools() {
        List<ToolSpec> specs = MvpToolSpecs.mvp_tool_specs();

        assertThat(specs).hasSize(13);
        assertThat(specs)
                .extracting(ToolSpec::name)
                .containsExactlyInAnyOrder(
                        "read_file",
                        "write_file",
                        "edit_file",
                        "glob_search",
                        "grep_search",
                        "bash",
                        "TodoWrite",
                        "Sleep",
                        "ToolSearch",
                        "StructuredOutput",
                        "EnterPlanMode",
                        "ExitPlanMode",
                        "SendUserMessage");
    }

    @Test
    void every_spec_has_an_input_schema_object() {
        for (ToolSpec spec : MvpToolSpecs.mvp_tool_specs()) {
            assertThat(spec.input_schema()).as("schema for %s", spec.name()).isNotNull();
            assertThat(spec.input_schema().get("type").asText()).isEqualTo("object");
        }
    }

    @Test
    void all_tool_specs_lists_exactly_fifty_tools_matching_rust() {
        List<String> names =
                MvpToolSpecs.all_tool_specs().stream().map(ToolSpec::name).toList();

        assertThat(names).hasSize(50);
        assertThat(names)
                .containsExactlyInAnyOrder(
                        // Workspace files & shell (6).
                        "bash",
                        "read_file",
                        "write_file",
                        "edit_file",
                        "glob_search",
                        "grep_search",
                        // Web (2).
                        "WebFetch",
                        "WebSearch",
                        // Session/tasks/planning (11).
                        "TodoWrite",
                        "Skill",
                        "Agent",
                        "ToolSearch",
                        "NotebookEdit",
                        "Sleep",
                        "SendUserMessage",
                        "Config",
                        "EnterPlanMode",
                        "ExitPlanMode",
                        "StructuredOutput",
                        // Subprocess (2).
                        "REPL",
                        "PowerShell",
                        // Interactive (1).
                        "AskUserQuestion",
                        // Task registry (7).
                        "TaskCreate",
                        "RunTaskPacket",
                        "TaskGet",
                        "TaskList",
                        "TaskStop",
                        "TaskUpdate",
                        "TaskOutput",
                        // Worker registry (9).
                        "WorkerCreate",
                        "WorkerGet",
                        "WorkerObserve",
                        "WorkerResolveTrust",
                        "WorkerAwaitReady",
                        "WorkerSendPrompt",
                        "WorkerRestart",
                        "WorkerTerminate",
                        "WorkerObserveCompletion",
                        // Team / Cron (5).
                        "TeamCreate",
                        "TeamDelete",
                        "CronCreate",
                        "CronDelete",
                        "CronList",
                        // LSP (1).
                        "LSP",
                        // MCP (5).
                        "ListMcpResources",
                        "ReadMcpResource",
                        "McpAuth",
                        "RemoteTrigger",
                        "MCP",
                        // Test-only (1).
                        "TestingPermission");
    }

    @Test
    void all_specs_have_input_schema_objects() {
        for (ToolSpec spec : MvpToolSpecs.all_tool_specs()) {
            assertThat(spec.input_schema()).as("schema for %s", spec.name()).isNotNull();
            assertThat(spec.input_schema().get("type").asText())
                    .as("type for %s", spec.name())
                    .isEqualTo("object");
        }
    }

    @Test
    void all_tool_specs_has_unique_tool_names() {
        List<String> names =
                MvpToolSpecs.all_tool_specs().stream().map(ToolSpec::name).toList();

        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void all_tool_specs_with_empty_mcp_bridge_matches_base_specs() {
        List<ToolSpec> base = MvpToolSpecs.all_tool_specs();
        List<ToolSpec> with_empty = MvpToolSpecs.all_tool_specs(Optional.empty());

        assertThat(with_empty).hasSameSizeAs(base);
    }

    @Test
    void all_tool_specs_with_mcp_bridge_appends_dynamic_specs() {
        McpToolBridge bridge = new McpToolBridge();
        bridge.register_server(
                "alpha",
                McpToolBridge.ConnectionStatus.CONNECTED,
                List.of(new McpToolBridge.McpToolInfo("ping", Optional.empty(), Optional.empty())),
                List.of(),
                Optional.empty());
        McpToolBridgeAdapter adapter = new McpToolBridgeAdapter(bridge);

        List<ToolSpec> all = MvpToolSpecs.all_tool_specs(Optional.of(adapter));

        assertThat(all).hasSize(MvpToolSpecs.all_tool_specs().size() + 1);
        assertThat(all.stream().map(ToolSpec::name).toList()).contains("mcp__alpha__ping");
    }

    @Test
    void exposes_mvp_tools_in_full_surface() {
        List<String> names =
                MvpToolSpecs.all_tool_specs().stream().map(ToolSpec::name).toList();
        assertThat(names)
                .contains(
                        "bash",
                        "read_file",
                        "WebFetch",
                        "WebSearch",
                        "TodoWrite",
                        "Skill",
                        "Agent",
                        "ToolSearch",
                        "NotebookEdit",
                        "Sleep",
                        "SendUserMessage",
                        "Config",
                        "EnterPlanMode",
                        "ExitPlanMode",
                        "StructuredOutput",
                        "REPL",
                        "PowerShell",
                        "WorkerCreate",
                        "WorkerObserve",
                        "WorkerAwaitReady",
                        "WorkerSendPrompt");
    }

    @Test
    void does_not_include_pre_port_extras() {
        List<String> names =
                MvpToolSpecs.all_tool_specs().stream().map(ToolSpec::name).toList();

        // These were over-shot by the earlier port and are not in Rust's mvp_tool_specs.
        assertThat(names)
                .doesNotContain(
                        "Brief",
                        "EnterWorktree",
                        "ExitWorktree",
                        "Monitor",
                        "mcp__claude_ai_Gmail__authenticate",
                        "mcp__claude_ai_Gmail__complete_authentication",
                        "mcp__claude_ai_Google_Calendar__authenticate",
                        "mcp__claude_ai_Google_Calendar__complete_authentication",
                        "mcp__claude_ai_Google_Drive__authenticate",
                        "mcp__claude_ai_Google_Drive__complete_authentication");
    }
}
