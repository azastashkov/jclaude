package org.jclaude.runtime.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcCodec;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcResponse;
import org.jclaude.runtime.mcp.jsonrpc.transport.StdioTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Stdio integration tests that spawn external Python helpers as MCP servers. */
@EnabledOnOs({OS.LINUX, OS.MAC})
class McpStdioIntegrationTest {

    @Test
    void spawns_stdio_process_and_round_trips_io() throws Exception {
        // Spawn the echo script via plain ProcessBuilder (not StdioTransport) since the script
        // emits newline-delimited text rather than Content-Length-framed JSON-RPC. Mirrors the
        // Rust {@code spawns_stdio_process_and_round_trips_io} test which uses the lower-level
        // {@code McpStdioProcess::write_line}/{@code read_line} helpers for this fixture.
        Path script = FakeMcpScripts.writeEchoScript();
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", script.toString());
            pb.environment().put("MCP_TEST_TOKEN", "secret-value");
            Process p = pb.start();
            try {
                var stdoutReader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String ready = stdoutReader.readLine();
                assertThat(ready).isEqualTo("READY:secret-value");

                p.getOutputStream().write("ping from client\n".getBytes(StandardCharsets.UTF_8));
                p.getOutputStream().flush();
                p.getOutputStream().close();

                String echoed = stdoutReader.readLine();
                assertThat(echoed).isEqualTo("ECHO:ping from client");

                int exit = p.waitFor();
                assertThat(exit).isZero();
            } finally {
                if (p.isAlive()) {
                    p.destroy();
                    p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
        } finally {
            FakeMcpScripts.cleanup(script);
        }
    }

    @Test
    void rejects_non_stdio_bootstrap() {
        McpServerSpec spec = McpServerSpec.http("remote", "https://example.test/mcp", Map.of());
        assertThatThrownBy(() -> McpStdio.spawn_stdio(spec)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void direct_spawn_uses_transport_env() throws Exception {
        // Spawn directly via ProcessBuilder (echo fixture emits newline-delimited text, not framed
        // JSON-RPC) and confirm the {@code MCP_TEST_TOKEN} env var reaches the child.
        Path script = FakeMcpScripts.writeEchoScript();
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", script.toString());
            pb.environment().put("MCP_TEST_TOKEN", "direct-secret");
            Process p = pb.start();
            try {
                var stdoutReader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String ready = stdoutReader.readLine();
                assertThat(ready).isEqualTo("READY:direct-secret");
            } finally {
                p.destroy();
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            FakeMcpScripts.cleanup(script);
        }
    }

    @Test
    void round_trips_initialize_request_and_response_over_stdio_frames() throws Exception {
        Path script = FakeMcpScripts.writeFakeMcpServerScript();
        try {
            McpServerSpec spec = McpServerSpec.stdio("stdio", "python3", List.of(script.toString()), Map.of());
            try (StdioTransport transport = McpStdio.spawn_stdio(spec)) {
                McpClient client = new McpClient("stdio", transport);
                JsonRpcResponse init = client.initialize(Duration.ofSeconds(5));
                assertThat(init.error()).isNull();
                assertThat(init.result().get("protocolVersion").asText()).isEqualTo(McpClient.PROTOCOL_VERSION);
                assertThat(init.result().get("serverInfo").get("name").asText()).isEqualTo("fake-mcp");
            }
        } finally {
            FakeMcpScripts.cleanup(script);
        }
    }

    @Test
    void lists_tools_calls_tool_and_reads_resources_over_jsonrpc() throws Exception {
        Path script = FakeMcpScripts.writeFakeMcpServerScript();
        try {
            McpServerSpec spec = McpServerSpec.stdio("fake", "python3", List.of(script.toString()), Map.of());
            try (StdioTransport transport = McpStdio.spawn_stdio(spec)) {
                McpClient client = new McpClient("fake", transport);
                client.initialize(Duration.ofSeconds(5));
                List<McpTool> tools = client.list_tools(Duration.ofSeconds(5));
                assertThat(tools).hasSize(1);
                assertThat(tools.get(0).name()).isEqualTo("echo");

                ObjectNode args = JsonRpcCodec.mapper().createObjectNode().put("text", "hello");
                JsonRpcResponse callResponse = client.call_tool("echo", args, Duration.ofSeconds(5));
                assertThat(callResponse.error()).isNull();
                JsonNode call_result = callResponse.result();
                assertThat(call_result.get("isError").asBoolean()).isFalse();
                assertThat(call_result.get("structuredContent").get("echoed").asText())
                        .isEqualTo("hello");
                assertThat(call_result.get("content").get(0).get("type").asText())
                        .isEqualTo("text");
                assertThat(call_result.get("content").get(0).get("text").asText())
                        .isEqualTo("echo:hello");

                List<McpResource> resources = client.list_resources(Duration.ofSeconds(5));
                assertThat(resources).hasSize(1);
                assertThat(resources.get(0).uri()).isEqualTo("file://guide.txt");
                assertThat(resources.get(0).mime_type()).contains("text/plain");

                JsonNode read = client.read_resource("file://guide.txt", Duration.ofSeconds(5));
                assertThat(read.get("contents").get(0).get("text").asText()).isEqualTo("contents for file://guide.txt");
            }
        } finally {
            FakeMcpScripts.cleanup(script);
        }
    }

    @Test
    void surfaces_jsonrpc_errors_from_tool_calls() throws Exception {
        Path script = FakeMcpScripts.writeFakeMcpServerScript();
        try {
            McpServerSpec spec = McpServerSpec.stdio("fake", "python3", List.of(script.toString()), Map.of());
            try (StdioTransport transport = McpStdio.spawn_stdio(spec)) {
                McpClient client = new McpClient("fake", transport);
                client.initialize(Duration.ofSeconds(5));
                JsonRpcResponse response =
                        client.call_tool("fail", JsonRpcCodec.mapper().createObjectNode(), Duration.ofSeconds(5));
                assertThat(response.error()).isNotNull();
                assertThat(response.error().code()).isEqualTo(-32001);
                assertThat(response.error().message()).isEqualTo("tool failed");
            }
        } finally {
            FakeMcpScripts.cleanup(script);
        }
    }

    @Test
    void manager_discovers_tools_from_stdio_config() throws Exception {
        Path script = FakeMcpScripts.writeManagerMcpServerScript();
        Path log = script.getParent().resolve("alpha.log");
        try {
            Map<String, McpServerSpec> servers = managerSpec("alpha", script, log);
            McpServerManager manager = McpServerManager.from_servers(servers);

            List<ManagedMcpTool> tools = manager.discover_tools();
            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).server_name()).isEqualTo("alpha");
            assertThat(tools.get(0).raw_name()).isEqualTo("echo");
            assertThat(tools.get(0).qualified_name()).isEqualTo(Mcp.mcp_tool_name("alpha", "echo"));
            assertThat(manager.unsupported_servers()).isEmpty();

            manager.shutdown();
        } finally {
            FakeMcpScripts.cleanup(script);
        }
    }

    @Test
    void manager_routes_tool_calls_to_correct_server() throws Exception {
        Path script = FakeMcpScripts.writeManagerMcpServerScript();
        Path alphaLog = script.getParent().resolve("alpha.log");
        Path betaLog = script.getParent().resolve("beta.log");
        try {
            Map<String, McpServerSpec> servers = new TreeMap<>();
            servers.putAll(managerSpec("alpha", script, alphaLog));
            servers.putAll(managerSpec("beta", script, betaLog));
            McpServerManager manager = McpServerManager.from_servers(servers);

            assertThat(manager.discover_tools()).hasSize(2);

            JsonRpcResponse alpha = manager.call_tool(
                    Mcp.mcp_tool_name("alpha", "echo"),
                    JsonRpcCodec.mapper().createObjectNode().put("text", "hello"));
            JsonRpcResponse beta = manager.call_tool(
                    Mcp.mcp_tool_name("beta", "echo"),
                    JsonRpcCodec.mapper().createObjectNode().put("text", "world"));
            assertThat(alpha.result().get("structuredContent").get("server").asText())
                    .isEqualTo("alpha");
            assertThat(beta.result().get("structuredContent").get("server").asText())
                    .isEqualTo("beta");

            manager.shutdown();
        } finally {
            FakeMcpScripts.cleanup(script);
        }
    }

    @Test
    void manager_lists_and_reads_resources_from_stdio_servers() throws Exception {
        Path script = FakeMcpScripts.writeFakeMcpServerScript();
        try {
            Map<String, McpServerSpec> servers =
                    Map.of("alpha", McpServerSpec.stdio("alpha", "python3", List.of(script.toString()), Map.of()));
            McpServerManager manager = McpServerManager.from_servers(servers);

            List<McpResource> listed = manager.list_resources("alpha");
            assertThat(listed).hasSize(1);
            assertThat(listed.get(0).uri()).isEqualTo("file://guide.txt");

            JsonNode read = manager.read_resource("alpha", "file://guide.txt");
            assertThat(read.get("contents").get(0).get("text").asText()).isEqualTo("contents for file://guide.txt");

            manager.shutdown();
        } finally {
            FakeMcpScripts.cleanup(script);
        }
    }

    @Test
    void manager_discovery_report_keeps_healthy_servers_when_one_server_fails() throws Exception {
        Path script = FakeMcpScripts.writeManagerMcpServerScript();
        Path alphaLog = script.getParent().resolve("alpha.log");
        Path broken = FakeMcpScripts.writeInitializeDisconnectScript();
        try {
            Map<String, McpServerSpec> servers = new TreeMap<>();
            servers.putAll(managerSpec("alpha", script, alphaLog));
            servers.put("broken", McpServerSpec.stdio("broken", "python3", List.of(broken.toString()), Map.of()));
            McpServerManager manager = McpServerManager.from_servers(servers);

            McpToolDiscoveryReport report = manager.discover_tools_best_effort();

            assertThat(report.tools()).hasSize(1);
            assertThat(report.tools().get(0).qualified_name()).isEqualTo(Mcp.mcp_tool_name("alpha", "echo"));
            assertThat(report.failed_servers()).hasSize(1);
            assertThat(report.failed_servers().get(0).server_name()).isEqualTo("broken");
            assertThat(report.failed_servers().get(0).phase()).isEqualTo(McpLifecyclePhase.INITIALIZE_HANDSHAKE);
            assertThat(report.failed_servers().get(0).recoverable()).isFalse();
            assertThat(report.failed_servers().get(0).context()).containsEntry("method", "initialize");
            assertThat(report.degraded_startup()).isPresent();
            McpDegradedReport degraded = report.degraded_startup().get();
            assertThat(degraded.working_servers()).containsExactly("alpha");
            assertThat(degraded.failed_servers().get(0).server_name()).isEqualTo("broken");
            assertThat(degraded.available_tools()).containsExactly(Mcp.mcp_tool_name("alpha", "echo"));

            // Healthy server should still be callable.
            JsonRpcResponse response = manager.call_tool(
                    Mcp.mcp_tool_name("alpha", "echo"),
                    JsonRpcCodec.mapper().createObjectNode().put("text", "ok"));
            assertThat(response.result().get("structuredContent").get("echoed").asText())
                    .isEqualTo("ok");

            manager.shutdown();
        } finally {
            FakeMcpScripts.cleanup(script);
            FakeMcpScripts.cleanup(broken);
        }
    }

    @Test
    void manager_records_unsupported_non_stdio_servers_without_panicking() {
        Map<String, McpServerSpec> servers = Map.of(
                "http", McpServerSpec.http("http", "https://example.test/mcp", Map.of()),
                "ws", McpServerSpec.websocket("ws", "wss://example.test/mcp", Map.of()),
                "sse", McpServerSpec.sse("sse", "https://example.test/mcp", Map.of()));

        McpServerManager manager = McpServerManager.from_servers(servers);
        List<UnsupportedMcpServer> unsupported = manager.unsupported_servers();

        assertThat(unsupported).hasSize(3);
        assertThat(unsupported.stream().map(UnsupportedMcpServer::server_name))
                .containsExactlyInAnyOrder("http", "ws", "sse");
    }

    @Test
    void manager_shutdown_terminates_spawned_children_and_is_idempotent() throws Exception {
        Path script = FakeMcpScripts.writeManagerMcpServerScript();
        Path log = script.getParent().resolve("alpha.log");
        try {
            McpServerManager manager = McpServerManager.from_servers(managerSpec("alpha", script, log));
            manager.discover_tools();
            manager.shutdown();
            manager.shutdown();
        } finally {
            FakeMcpScripts.cleanup(script);
        }
    }

    @Test
    void manager_reuses_spawned_server_between_discovery_and_call() throws Exception {
        Path script = FakeMcpScripts.writeManagerMcpServerScript();
        Path log = script.getParent().resolve("alpha.log");
        try {
            McpServerManager manager = McpServerManager.from_servers(managerSpec("alpha", script, log));

            manager.discover_tools();
            JsonRpcResponse response = manager.call_tool(
                    Mcp.mcp_tool_name("alpha", "echo"),
                    JsonRpcCodec.mapper().createObjectNode().put("text", "reuse"));
            assertThat(response.result()
                            .get("structuredContent")
                            .get("initializeCount")
                            .asInt())
                    .isEqualTo(1);

            String logContents = Files.readString(log, StandardCharsets.UTF_8);
            assertThat(logContents.lines().filter("initialize"::equals).count()).isEqualTo(1);
            assertThat(logContents.lines().toList()).containsExactly("initialize", "tools/list", "tools/call");

            manager.shutdown();
        } finally {
            FakeMcpScripts.cleanup(script);
        }
    }

    @Test
    void manager_reports_unknown_qualified_tool_name() throws Exception {
        Path script = FakeMcpScripts.writeManagerMcpServerScript();
        Path log = script.getParent().resolve("alpha.log");
        try {
            McpServerManager manager = McpServerManager.from_servers(managerSpec("alpha", script, log));
            assertThatThrownBy(() -> manager.call_tool(
                            Mcp.mcp_tool_name("alpha", "missing"),
                            JsonRpcCodec.mapper().createObjectNode()))
                    .isInstanceOf(McpException.UnknownTool.class);
        } finally {
            FakeMcpScripts.cleanup(script);
        }
    }

    private Map<String, McpServerSpec> managerSpec(String label, Path script, Path log) {
        return Map.of(
                label,
                McpServerSpec.stdio(
                        label,
                        "python3",
                        List.of(script.toString()),
                        Map.of("MCP_SERVER_LABEL", label, "MCP_LOG_PATH", log.toString())));
    }
}
