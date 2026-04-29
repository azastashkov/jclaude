package org.jclaude.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.runtime.lsp.LspClient;
import org.jclaude.runtime.lsp.LspRegistry;
import org.jclaude.runtime.mcp.McpToolBridge;
import org.jclaude.runtime.task.TaskRegistry;
import org.jclaude.runtime.team.CronRegistry;
import org.jclaude.runtime.team.TeamRegistry;
import org.jclaude.runtime.worker.Worker;
import org.jclaude.runtime.worker.WorkerRegistry;
import org.jclaude.tools.bridge.McpToolBridgeAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolDispatcherPhase4Test {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    private ToolDispatcher dispatcher_with(
            Path workspace,
            Optional<McpToolBridgeAdapter> bridge,
            Optional<WorkerRegistry> workers,
            Optional<Map<String, LspClient>> lsp_clients) {
        return new ToolDispatcher(
                workspace,
                new GlobalToolRegistry(MvpToolSpecs.all_tool_specs()),
                new TodoStore(),
                new PlanModeState(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                org.jclaude.plugins.PluginRegistry.empty(),
                new TaskRegistry(),
                new TeamRegistry(),
                new CronRegistry(),
                new LspRegistry(),
                bridge,
                workers,
                lsp_clients);
    }

    // ----- MCP wiring -----

    @Test
    void mcp_returns_no_mcp_servers_configured_when_bridge_absent(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_with(workspace, Optional.empty(), Optional.empty(), Optional.empty());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("server", "demo");
        input.put("tool", "ping");

        ToolResult result = dispatcher.execute("MCP", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("status").asText()).isEqualTo("no_mcp_servers_configured");
    }

    @Test
    void mcp_dispatches_to_bridge_when_present(@TempDir Path workspace) throws IOException {
        McpToolBridge bridge = new McpToolBridge();
        bridge.register_server(
                "demo",
                McpToolBridge.ConnectionStatus.CONNECTED,
                List.of(new McpToolBridge.McpToolInfo("ping", Optional.empty(), Optional.empty())),
                List.of(),
                Optional.empty());
        McpToolBridgeAdapter adapter = new McpToolBridgeAdapter(bridge);
        ToolDispatcher dispatcher =
                dispatcher_with(workspace, Optional.of(adapter), Optional.empty(), Optional.empty());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("server", "demo");
        input.put("tool", "ping");

        ToolResult result = dispatcher.execute("MCP", input);

        // The bridge has no manager bound so it raises a runtime error — but the dispatcher must
        // at least delegate (not return the Phase 3 stub).
        assertThat(result.is_error()).isTrue();
        assertThat(result.output()).contains("manager");
    }

    @Test
    void list_mcp_resources_dispatches_to_bridge_when_present(@TempDir Path workspace) throws IOException {
        McpToolBridge bridge = new McpToolBridge();
        bridge.register_server(
                "demo",
                McpToolBridge.ConnectionStatus.CONNECTED,
                List.of(),
                List.of(new McpToolBridge.McpResourceInfo(
                        "memory://hello", "hello", Optional.of("a greeting"), Optional.of("text/plain"))),
                Optional.empty());
        McpToolBridgeAdapter adapter = new McpToolBridgeAdapter(bridge);
        ToolDispatcher dispatcher =
                dispatcher_with(workspace, Optional.of(adapter), Optional.empty(), Optional.empty());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("server", "demo");

        ToolResult result = dispatcher.execute("ListMcpResources", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("count").asInt()).isEqualTo(1);
        ArrayNode resources = (ArrayNode) payload.get("resources");
        assertThat(resources).hasSize(1);
        assertThat(resources.get(0).get("uri").asText()).isEqualTo("memory://hello");
    }

    @Test
    void read_mcp_resource_returns_no_mcp_servers_configured_when_bridge_absent(@TempDir Path workspace)
            throws IOException {
        ToolDispatcher dispatcher = dispatcher_with(workspace, Optional.empty(), Optional.empty(), Optional.empty());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("uri", "memory://hello");
        input.put("server", "demo");

        ToolResult result = dispatcher.execute("ReadMcpResource", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("status").asText()).isEqualTo("no_mcp_servers_configured");
    }

    @Test
    void read_mcp_resource_dispatches_to_bridge_when_present(@TempDir Path workspace) throws IOException {
        McpToolBridge bridge = new McpToolBridge();
        bridge.register_server(
                "demo",
                McpToolBridge.ConnectionStatus.CONNECTED,
                List.of(),
                List.of(new McpToolBridge.McpResourceInfo(
                        "memory://hello", "hello", Optional.of("a greeting"), Optional.empty())),
                Optional.empty());
        McpToolBridgeAdapter adapter = new McpToolBridgeAdapter(bridge);
        ToolDispatcher dispatcher =
                dispatcher_with(workspace, Optional.of(adapter), Optional.empty(), Optional.empty());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("server", "demo");
        input.put("uri", "memory://hello");

        ToolResult result = dispatcher.execute("ReadMcpResource", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("uri").asText()).isEqualTo("memory://hello");
        assertThat(payload.get("name").asText()).isEqualTo("hello");
    }

    @Test
    void dynamic_mcp_tool_name_routes_through_bridge(@TempDir Path workspace) throws IOException {
        McpToolBridge bridge = new McpToolBridge();
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        bridge.register_server(
                "demo",
                McpToolBridge.ConnectionStatus.CONNECTED,
                List.of(new McpToolBridge.McpToolInfo("ping", Optional.of("p"), Optional.of(schema))),
                List.of(),
                Optional.empty());
        McpToolBridgeAdapter adapter = new McpToolBridgeAdapter(bridge);
        ToolDispatcher dispatcher =
                dispatcher_with(workspace, Optional.of(adapter), Optional.empty(), Optional.empty());

        ToolResult result = dispatcher.execute("mcp__demo__ping", MAPPER.createObjectNode());

        // Bridge has no manager, so the call raises — but routing happened (no UnsupportedToolException).
        assertThat(result.is_error()).isTrue();
        assertThat(result.output()).contains("manager");
    }

    @Test
    void unknown_dynamic_mcp_tool_falls_through_to_unsupported_when_bridge_absent(@TempDir Path workspace) {
        ToolDispatcher dispatcher = dispatcher_with(workspace, Optional.empty(), Optional.empty(), Optional.empty());

        UnsupportedToolException error =
                catchUnsupportedTool(() -> dispatcher.execute("mcp__custom__zzz", MAPPER.createObjectNode()));

        assertThat(error).isNotNull();
        assertThat(error.getMessage()).contains("mcp__custom__zzz");
    }

    // ----- Worker wiring -----

    @Test
    void worker_get_returns_phase_3_stub_when_registry_absent(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_with(workspace, Optional.empty(), Optional.empty(), Optional.empty());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", "worker_demo");

        ToolResult result = dispatcher.execute("WorkerGet", input);

        assertThat(result.is_error()).isTrue();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("error").asText()).isEqualTo("not yet implemented");
    }

    @Test
    void worker_create_dispatches_to_runtime_worker_registry(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        ToolDispatcher dispatcher =
                dispatcher_with(workspace, Optional.empty(), Optional.of(workers), Optional.empty());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("cwd", workspace.toString());
        ArrayNode trusted = input.putArray("trusted_roots");
        trusted.add(workspace.toString());
        input.put("auto_recover_prompt_misdelivery", true);

        ToolResult result = dispatcher.execute("WorkerCreate", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("worker_id").asText()).startsWith("worker_");
        assertThat(payload.get("cwd").asText()).isEqualTo(workspace.toString());
        assertThat(workers.get(payload.get("worker_id").asText())).isPresent();
    }

    @Test
    void worker_get_returns_state_for_known_worker(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        Worker created = workers.create(workspace.toString(), List.of(), false);
        ToolDispatcher dispatcher =
                dispatcher_with(workspace, Optional.empty(), Optional.of(workers), Optional.empty());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", created.worker_id());

        ToolResult result = dispatcher.execute("WorkerGet", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("worker_id").asText()).isEqualTo(created.worker_id());
        assertThat(payload.get("status").asText()).isEqualTo("spawning");
    }

    @Test
    void worker_get_returns_error_for_unknown_worker(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        ToolDispatcher dispatcher =
                dispatcher_with(workspace, Optional.empty(), Optional.of(workers), Optional.empty());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", "worker_unknown");

        ToolResult result = dispatcher.execute("WorkerGet", input);

        assertThat(result.is_error()).isTrue();
        assertThat(result.output()).contains("worker not found");
    }

    @Test
    void worker_observe_dispatches_to_runtime_worker_registry(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        Worker created = workers.create(workspace.toString(), List.of(), false);
        ToolDispatcher dispatcher =
                dispatcher_with(workspace, Optional.empty(), Optional.of(workers), Optional.empty());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", created.worker_id());
        input.put("screen_text", "ready for prompt");

        ToolResult result = dispatcher.execute("WorkerObserve", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("worker_id").asText()).isEqualTo(created.worker_id());
        assertThat(payload.get("status").asText()).isEqualTo("ready_for_prompt");
    }

    @Test
    void worker_await_ready_returns_snapshot(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        Worker created = workers.create(workspace.toString(), List.of(), false);
        ToolDispatcher dispatcher =
                dispatcher_with(workspace, Optional.empty(), Optional.of(workers), Optional.empty());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", created.worker_id());

        ToolResult result = dispatcher.execute("WorkerAwaitReady", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("worker_id").asText()).isEqualTo(created.worker_id());
        assertThat(payload.has("ready")).isTrue();
        assertThat(payload.has("blocked")).isTrue();
    }

    @Test
    void worker_terminate_marks_worker_finished(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        Worker created = workers.create(workspace.toString(), List.of(), false);
        ToolDispatcher dispatcher =
                dispatcher_with(workspace, Optional.empty(), Optional.of(workers), Optional.empty());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", created.worker_id());

        ToolResult result = dispatcher.execute("WorkerTerminate", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("status").asText()).isEqualTo("finished");
    }

    @Test
    void worker_restart_resets_lifecycle(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        Worker created = workers.create(workspace.toString(), List.of(), false);
        ToolDispatcher dispatcher =
                dispatcher_with(workspace, Optional.empty(), Optional.of(workers), Optional.empty());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", created.worker_id());

        ToolResult result = dispatcher.execute("WorkerRestart", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("status").asText()).isEqualTo("spawning");
    }

    @Test
    void worker_observe_completion_records_finish_reason(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        Worker created = workers.create(workspace.toString(), List.of(), false);
        ToolDispatcher dispatcher =
                dispatcher_with(workspace, Optional.empty(), Optional.of(workers), Optional.empty());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", created.worker_id());
        input.put("finish_reason", "stop");
        input.put("tokens_output", 42);

        ToolResult result = dispatcher.execute("WorkerObserveCompletion", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("status").asText()).isEqualTo("finished");
    }

    // ----- LSP wiring -----

    @Test
    void lsp_falls_back_to_registry_when_no_clients_configured(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_with(workspace, Optional.empty(), Optional.empty(), Optional.empty());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("action", "diagnostics");

        ToolResult result = dispatcher.execute("LSP", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("count").asInt()).isEqualTo(0);
    }

    @Test
    void lsp_clients_optional_is_exposed(@TempDir Path workspace) {
        ToolDispatcher dispatcher =
                dispatcher_with(workspace, Optional.empty(), Optional.empty(), Optional.of(Map.of()));

        assertThat(dispatcher.lsp_clients_by_language()).isPresent();
        assertThat(dispatcher.lsp_clients_by_language().get()).isEmpty();
    }

    @Test
    void lsp_diagnostics_uses_registry_even_with_clients_configured(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher =
                dispatcher_with(workspace, Optional.empty(), Optional.empty(), Optional.of(Map.of()));
        ObjectNode input = MAPPER.createObjectNode();
        input.put("action", "diagnostics");

        ToolResult result = dispatcher.execute("LSP", input);

        // Diagnostics without a path hits the registry branch even when clients are configured.
        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("count").asInt()).isEqualTo(0);
    }

    // ----- Bridge accessors -----

    @Test
    void bridge_accessors_reflect_optional_state(@TempDir Path workspace) {
        McpToolBridgeAdapter adapter = new McpToolBridgeAdapter(new McpToolBridge());
        WorkerRegistry workers = new WorkerRegistry();
        ToolDispatcher dispatcher =
                dispatcher_with(workspace, Optional.of(adapter), Optional.of(workers), Optional.empty());

        assertThat(dispatcher.mcp_bridge()).contains(adapter);
        assertThat(dispatcher.worker_registry()).contains(workers);
        assertThat(dispatcher.lsp_clients_by_language()).isEmpty();
    }

    private static UnsupportedToolException catchUnsupportedTool(Runnable body) {
        try {
            body.run();
            return null;
        } catch (UnsupportedToolException e) {
            return e;
        }
    }
}
