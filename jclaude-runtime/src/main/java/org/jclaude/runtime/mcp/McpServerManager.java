package org.jclaude.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.jclaude.runtime.mcp.jsonrpc.JsonRpcResponse;
import org.jclaude.runtime.mcp.jsonrpc.transport.Transport;

/**
 * Manages a fleet of MCP servers — spawns each, performs the initialize handshake on demand, and
 * routes tool calls / resource lookups through the right client. Mirrors the Rust
 * {@code McpServerManager} from {@code mcp_stdio.rs}: stdio transports are managed natively;
 * other transport variants are accepted, spawned through their respective transports, and treated
 * uniformly via {@link McpClient}.
 *
 * <p>Unlike the Rust port the Java manager does <em>not</em> restrict itself to stdio — but tests
 * that mirror the upstream behaviour can still mark non-stdio servers as unsupported via
 * {@link #from_servers(Map, boolean)} with {@code stdio_only=true}.
 */
public final class McpServerManager {

    private final Map<String, ManagedServer> servers;
    private final List<UnsupportedMcpServer> unsupported_servers;
    private final Map<String, ToolRoute> tool_index = new LinkedHashMap<>();
    private final Object lock = new Object();

    public McpServerManager(Map<String, ManagedServer> servers, List<UnsupportedMcpServer> unsupported_servers) {
        this.servers = servers;
        this.unsupported_servers = unsupported_servers;
    }

    public static McpServerManager from_servers(Map<String, McpServerSpec> specs) {
        return from_servers(specs, true);
    }

    public static McpServerManager from_servers(Map<String, McpServerSpec> specs, boolean stdio_only) {
        Objects.requireNonNull(specs, "specs");
        Map<String, ManagedServer> servers = new TreeMap<>();
        List<UnsupportedMcpServer> unsupported = new ArrayList<>();
        for (Map.Entry<String, McpServerSpec> entry : specs.entrySet()) {
            McpServerSpec spec = entry.getValue();
            if (stdio_only && spec.kind() != McpServerSpec.Kind.STDIO) {
                unsupported.add(new UnsupportedMcpServer(
                        entry.getKey(),
                        spec.kind(),
                        "transport " + spec.kind() + " is not supported by McpServerManager"));
                continue;
            }
            servers.put(entry.getKey(), new ManagedServer(spec));
        }
        return new McpServerManager(servers, unsupported);
    }

    public List<UnsupportedMcpServer> unsupported_servers() {
        synchronized (lock) {
            return List.copyOf(unsupported_servers);
        }
    }

    public List<String> server_names() {
        synchronized (lock) {
            return List.copyOf(servers.keySet());
        }
    }

    public List<ManagedMcpTool> discover_tools() {
        synchronized (lock) {
            List<ManagedMcpTool> discovered = new ArrayList<>();
            for (String server_name : List.copyOf(servers.keySet())) {
                List<ManagedMcpTool> server_tools = discover_tools_for_server(server_name);
                clearRoutesFor(server_name);
                for (ManagedMcpTool tool : server_tools) {
                    tool_index.put(tool.qualified_name(), new ToolRoute(tool.server_name(), tool.raw_name()));
                    discovered.add(tool);
                }
            }
            return discovered;
        }
    }

    public McpToolDiscoveryReport discover_tools_best_effort() {
        synchronized (lock) {
            List<ManagedMcpTool> discovered_tools = new ArrayList<>();
            List<String> working = new ArrayList<>();
            List<McpDiscoveryFailure> failed = new ArrayList<>();
            for (String server_name : List.copyOf(servers.keySet())) {
                try {
                    List<ManagedMcpTool> server_tools = discover_tools_for_server(server_name);
                    working.add(server_name);
                    clearRoutesFor(server_name);
                    for (ManagedMcpTool tool : server_tools) {
                        tool_index.put(tool.qualified_name(), new ToolRoute(tool.server_name(), tool.raw_name()));
                        discovered_tools.add(tool);
                    }
                } catch (McpException error) {
                    clearRoutesFor(server_name);
                    failed.add(toDiscoveryFailure(error, server_name));
                }
            }

            List<McpFailedServer> degraded_failed = new ArrayList<>();
            for (McpDiscoveryFailure f : failed) {
                degraded_failed.add(new McpFailedServer(
                        f.server_name(),
                        f.phase(),
                        McpErrorSurface.create(
                                f.phase(), Optional.of(f.server_name()), f.error(), f.context(), f.recoverable())));
            }
            for (UnsupportedMcpServer u : unsupported_servers) {
                degraded_failed.add(new McpFailedServer(
                        u.server_name(),
                        McpLifecyclePhase.SERVER_REGISTRATION,
                        McpErrorSurface.create(
                                McpLifecyclePhase.SERVER_REGISTRATION,
                                Optional.of(u.server_name()),
                                u.reason(),
                                Map.of("transport", u.transport().toString()),
                                false)));
            }
            Optional<McpDegradedReport> degraded;
            if (!working.isEmpty() && !degraded_failed.isEmpty()) {
                List<String> tool_names = discovered_tools.stream()
                        .map(ManagedMcpTool::qualified_name)
                        .toList();
                degraded = Optional.of(McpDegradedReport.create(working, degraded_failed, tool_names, List.of()));
            } else {
                degraded = Optional.empty();
            }
            return new McpToolDiscoveryReport(discovered_tools, failed, unsupported_servers, degraded);
        }
    }

    public JsonRpcResponse call_tool(String qualified_tool_name, JsonNode arguments) {
        synchronized (lock) {
            ToolRoute route = tool_index.get(qualified_tool_name);
            if (route == null) {
                throw new McpException.UnknownTool(qualified_tool_name);
            }
            Duration timeout = McpStdio.resolved_tool_call_timeout(serverOrThrow(route.server_name).spec);
            ensureServerReady(route.server_name);
            ManagedServer server = serverOrThrow(route.server_name);
            try {
                return server.client.call_tool(route.raw_name, arguments, timeout);
            } catch (McpException error) {
                if (shouldResetServer(error)) {
                    resetServer(route.server_name);
                }
                throw error;
            }
        }
    }

    public List<McpResource> list_resources(String server_name) {
        synchronized (lock) {
            return retryOnce(server_name, () -> {
                ensureServerReady(server_name);
                return serverOrThrow(server_name)
                        .client
                        .list_resources(Duration.ofMillis(McpStdio.DEFAULT_LIST_TIMEOUT_MS));
            });
        }
    }

    public JsonNode read_resource(String server_name, String uri) {
        synchronized (lock) {
            return retryOnce(server_name, () -> {
                ensureServerReady(server_name);
                return serverOrThrow(server_name)
                        .client
                        .read_resource(uri, Duration.ofMillis(McpStdio.DEFAULT_LIST_TIMEOUT_MS));
            });
        }
    }

    public void shutdown() {
        synchronized (lock) {
            for (ManagedServer server : servers.values()) {
                if (server.client != null) {
                    server.client.close();
                    server.client = null;
                    server.initialized = false;
                }
            }
        }
    }

    private List<ManagedMcpTool> discover_tools_for_server(String server_name) {
        return retryOnce(server_name, () -> {
            ensureServerReady(server_name);
            ManagedServer server = serverOrThrow(server_name);
            List<McpTool> tools = server.client.list_tools(Duration.ofMillis(McpStdio.DEFAULT_LIST_TIMEOUT_MS));
            List<ManagedMcpTool> managed = new ArrayList<>(tools.size());
            for (McpTool tool : tools) {
                String qualified = Mcp.mcp_tool_name(server_name, tool.name());
                managed.add(new ManagedMcpTool(server_name, qualified, tool.name(), tool));
            }
            return managed;
        });
    }

    private <T> T retryOnce(String server_name, java.util.function.Supplier<T> body) {
        try {
            return body.get();
        } catch (McpException error) {
            if (isRetryable(error)) {
                resetServer(server_name);
                try {
                    return body.get();
                } catch (McpException retryError) {
                    if (shouldResetServer(retryError)) {
                        resetServer(server_name);
                    }
                    throw retryError;
                }
            }
            if (shouldResetServer(error)) {
                resetServer(server_name);
            }
            throw error;
        }
    }

    private void ensureServerReady(String server_name) {
        ManagedServer server = serverOrThrow(server_name);
        if (server.client != null && server.initialized && server.isAlive()) {
            return;
        }
        if (server.client != null && !server.isAlive()) {
            resetServer(server_name);
            server = serverOrThrow(server_name);
        }
        if (server.client == null) {
            server.client = new McpClient(server_name, McpStdio.spawn_transport(server.spec));
            server.initialized = false;
        }
        if (!server.initialized) {
            try {
                server.client.initialize(Duration.ofMillis(McpStdio.DEFAULT_INITIALIZE_TIMEOUT_MS));
                server.initialized = true;
            } catch (McpException error) {
                if (shouldResetServer(error)) {
                    resetServer(server_name);
                }
                throw error;
            }
        }
    }

    private void resetServer(String server_name) {
        ManagedServer server = servers.get(server_name);
        if (server == null) {
            return;
        }
        if (server.client != null) {
            server.client.close();
            server.client = null;
        }
        server.initialized = false;
    }

    private static boolean isRetryable(McpException error) {
        return error instanceof McpException.TransportFailure || error instanceof McpException.TimeoutFailure;
    }

    private static boolean shouldResetServer(McpException error) {
        return error instanceof McpException.TransportFailure
                || error instanceof McpException.TimeoutFailure
                || error instanceof McpException.InvalidResponse;
    }

    private ManagedServer serverOrThrow(String server_name) {
        ManagedServer server = servers.get(server_name);
        if (server == null) {
            throw new McpException.UnknownServer(server_name);
        }
        return server;
    }

    private void clearRoutesFor(String server_name) {
        Iterator<Map.Entry<String, ToolRoute>> iter = tool_index.entrySet().iterator();
        while (iter.hasNext()) {
            if (iter.next().getValue().server_name.equals(server_name)) {
                iter.remove();
            }
        }
    }

    private static McpDiscoveryFailure toDiscoveryFailure(McpException error, String server_name) {
        return new McpDiscoveryFailure(
                server_name, error.phase(), error.getMessage(), error.recoverable(), error.error_context());
    }

    /** Internal per-server state; package-private for the manager. */
    public static final class ManagedServer {

        final McpServerSpec spec;
        McpClient client;
        boolean initialized;

        ManagedServer(McpServerSpec spec) {
            this.spec = spec;
        }

        public McpServerSpec spec() {
            return spec;
        }

        public boolean isAlive() {
            if (client == null) {
                return false;
            }
            Transport transport = client.transport();
            if (transport instanceof org.jclaude.runtime.mcp.jsonrpc.transport.StdioTransport stdio) {
                return stdio.isAlive();
            }
            return true;
        }
    }

    /** Internal tool-routing key. */
    private record ToolRoute(String server_name, String raw_name) {}
}
