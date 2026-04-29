package org.jclaude.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.commands.SkillsHandler;
import org.jclaude.plugins.PluginRegistry;
import org.jclaude.plugins.PluginTool;
import org.jclaude.plugins.PluginToolExecutor;
import org.jclaude.runtime.bash.Bash;
import org.jclaude.runtime.bash.BashCommandInput;
import org.jclaude.runtime.bash.BashCommandOutput;
import org.jclaude.runtime.files.EditFile;
import org.jclaude.runtime.files.GlobSearch;
import org.jclaude.runtime.files.GrepSearch;
import org.jclaude.runtime.files.ReadFile;
import org.jclaude.runtime.files.WriteFile;
import org.jclaude.runtime.lsp.LspAction;
import org.jclaude.runtime.lsp.LspClient;
import org.jclaude.runtime.lsp.LspRegistry;
import org.jclaude.runtime.mcp.McpToolBridge;
import org.jclaude.runtime.task.Task;
import org.jclaude.runtime.task.TaskPacket;
import org.jclaude.runtime.task.TaskRegistry;
import org.jclaude.runtime.task.TaskScope;
import org.jclaude.runtime.team.CronEntry;
import org.jclaude.runtime.team.CronRegistry;
import org.jclaude.runtime.team.Team;
import org.jclaude.runtime.team.TeamRegistry;
import org.jclaude.runtime.worker.Worker;
import org.jclaude.runtime.worker.WorkerReadySnapshot;
import org.jclaude.runtime.worker.WorkerRegistry;
import org.jclaude.runtime.worker.WorkerTaskReceipt;
import org.jclaude.tools.bridge.McpToolBridgeAdapter;

/**
 * Phase 3 tool dispatcher — maps a tool name + JSON input to a concrete handler and serializes
 * the result back to JSON. Mirrors the giant {@code match} block in the upstream Rust
 * {@code execute_tool_with_enforcer} function (around L1199 of
 * {@code claw-code/rust/crates/tools/src/lib.rs}) and routes both the 13 Phase-1 MVP tools and
 * the wider catalogue (Skill, Agent, NotebookEdit, Web*, Task*, Worker*, Team*, Cron*, LSP, MCP*,
 * Config, REPL, PowerShell, ...).
 *
 * <p>Tools that are not in the dispatch table raise {@link UnsupportedToolException} — callers
 * that want graceful degradation should catch and convert to an error {@link ToolResult}
 * themselves.
 */
public final class ToolDispatcher implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(ToolDispatcher.class.getName());
    private static final ObjectMapper MAPPER = JclaudeMappers.standard();
    private static final long REMOTE_TRIGGER_TIMEOUT_SECONDS = 30L;
    private static final int REMOTE_TRIGGER_MAX_BODY = 8192;
    private static final long MAX_SLEEP_DURATION_MS = 300_000L;
    private static final String NOT_YET_IMPLEMENTED = "not yet implemented";

    private final Path workspace_root;
    private final GlobalToolRegistry registry;
    private final TodoStore todo_store;
    private final PlanModeState plan_mode;
    private final PrintStream user_message_sink;
    private final PluginRegistry plugins;
    private final TaskRegistry task_registry;
    private final TeamRegistry team_registry;
    private final CronRegistry cron_registry;
    private final LspRegistry lsp_registry;
    private final Optional<McpToolBridgeAdapter> mcp_bridge;
    private final Optional<WorkerRegistry> worker_registry;
    private final Optional<Map<String, LspClient>> lsp_clients_by_language;
    private final HttpClient http_client;

    public ToolDispatcher(Path workspace_root) {
        this(
                workspace_root,
                GlobalToolRegistry.global(),
                TodoStore.global(),
                PlanModeState.global(),
                System.out,
                PluginRegistry.empty());
    }

    public ToolDispatcher(
            Path workspace_root,
            GlobalToolRegistry registry,
            TodoStore todo_store,
            PlanModeState plan_mode,
            PrintStream user_message_sink) {
        this(workspace_root, registry, todo_store, plan_mode, user_message_sink, PluginRegistry.empty());
    }

    public ToolDispatcher(
            Path workspace_root,
            GlobalToolRegistry registry,
            TodoStore todo_store,
            PlanModeState plan_mode,
            PrintStream user_message_sink,
            PluginRegistry plugins) {
        this(
                workspace_root,
                registry,
                todo_store,
                plan_mode,
                user_message_sink,
                plugins,
                new TaskRegistry(),
                new TeamRegistry(),
                new CronRegistry(),
                new LspRegistry());
    }

    public ToolDispatcher(
            Path workspace_root,
            GlobalToolRegistry registry,
            TodoStore todo_store,
            PlanModeState plan_mode,
            PrintStream user_message_sink,
            PluginRegistry plugins,
            TaskRegistry task_registry,
            TeamRegistry team_registry,
            CronRegistry cron_registry,
            LspRegistry lsp_registry) {
        this(
                workspace_root,
                registry,
                todo_store,
                plan_mode,
                user_message_sink,
                plugins,
                task_registry,
                team_registry,
                cron_registry,
                lsp_registry,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * Phase 4 wiring constructor — accepts optional runtime transports for the MCP bridge, the
     * worker registry, and a per-language LSP client map. When an optional is empty the
     * corresponding tool arm continues to return the Phase 3 "not yet implemented" stub.
     */
    public ToolDispatcher(
            Path workspace_root,
            GlobalToolRegistry registry,
            TodoStore todo_store,
            PlanModeState plan_mode,
            PrintStream user_message_sink,
            PluginRegistry plugins,
            TaskRegistry task_registry,
            TeamRegistry team_registry,
            CronRegistry cron_registry,
            LspRegistry lsp_registry,
            Optional<McpToolBridgeAdapter> mcp_bridge,
            Optional<WorkerRegistry> worker_registry,
            Optional<Map<String, LspClient>> lsp_clients_by_language) {
        if (workspace_root == null) {
            throw new IllegalArgumentException("workspace_root must not be null");
        }
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (todo_store == null) {
            throw new IllegalArgumentException("todo_store must not be null");
        }
        if (plan_mode == null) {
            throw new IllegalArgumentException("plan_mode must not be null");
        }
        if (user_message_sink == null) {
            throw new IllegalArgumentException("user_message_sink must not be null");
        }
        if (plugins == null) {
            throw new IllegalArgumentException("plugins must not be null");
        }
        if (task_registry == null) {
            throw new IllegalArgumentException("task_registry must not be null");
        }
        if (team_registry == null) {
            throw new IllegalArgumentException("team_registry must not be null");
        }
        if (cron_registry == null) {
            throw new IllegalArgumentException("cron_registry must not be null");
        }
        if (lsp_registry == null) {
            throw new IllegalArgumentException("lsp_registry must not be null");
        }
        this.workspace_root = workspace_root;
        this.registry = registry;
        this.todo_store = todo_store;
        this.plan_mode = plan_mode;
        this.user_message_sink = user_message_sink;
        this.plugins = plugins;
        this.task_registry = task_registry;
        this.team_registry = team_registry;
        this.cron_registry = cron_registry;
        this.lsp_registry = lsp_registry;
        this.mcp_bridge = mcp_bridge == null ? Optional.empty() : mcp_bridge;
        this.worker_registry = worker_registry == null ? Optional.empty() : worker_registry;
        this.lsp_clients_by_language = lsp_clients_by_language == null ? Optional.empty() : lsp_clients_by_language;
        this.http_client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Exposes the task registry so callers can pre-seed tasks for tests / harness flows. */
    public TaskRegistry task_registry() {
        return task_registry;
    }

    /** Exposes the team registry for the same reason. */
    public TeamRegistry team_registry() {
        return team_registry;
    }

    /** Exposes the cron registry for the same reason. */
    public CronRegistry cron_registry() {
        return cron_registry;
    }

    /** Exposes the LSP registry so callers can register fake servers in tests. */
    public LspRegistry lsp_registry() {
        return lsp_registry;
    }

    /** Exposes the MCP bridge adapter, if one was configured. */
    public Optional<McpToolBridgeAdapter> mcp_bridge() {
        return mcp_bridge;
    }

    /** Exposes the worker registry, if one was configured. */
    public Optional<WorkerRegistry> worker_registry() {
        return worker_registry;
    }

    /** Exposes the per-language LSP clients map, if one was configured. */
    public Optional<Map<String, LspClient>> lsp_clients_by_language() {
        return lsp_clients_by_language;
    }

    @Override
    public ToolResult execute(String tool_name, JsonNode tool_input) {
        if (tool_name == null) {
            throw new UnsupportedToolException("<null>");
        }
        JsonNode input = tool_input == null ? MAPPER.createObjectNode() : tool_input;
        try {
            return switch (tool_name) {
                    // Phase 1 MVP slice.
                case "read_file" -> dispatch_read_file(input);
                case "write_file" -> dispatch_write_file(input);
                case "edit_file" -> dispatch_edit_file(input);
                case "glob_search" -> dispatch_glob_search(input);
                case "grep_search" -> dispatch_grep_search(input);
                case "bash" -> dispatch_bash(input);
                case "TodoWrite" -> dispatch_todo_write(input);
                case "Sleep" -> dispatch_sleep(input);
                case "ToolSearch" -> dispatch_tool_search(input);
                case "StructuredOutput" -> dispatch_structured_output(input);
                case "EnterPlanMode" -> dispatch_plan_mode(true);
                case "ExitPlanMode" -> dispatch_plan_mode(false);
                case "SendUserMessage", "Brief" -> dispatch_send_user_message(input);

                    // Phase 3 tools.
                case "WebFetch" -> dispatch_web_fetch(input);
                case "WebSearch" -> dispatch_web_search(input);
                case "Skill" -> dispatch_skill(input);
                case "Agent" -> dispatch_agent(input);
                case "NotebookEdit" -> dispatch_notebook_edit(input);
                case "Config" -> dispatch_config(input);
                case "REPL" -> dispatch_repl(input);
                case "PowerShell" -> dispatch_powershell(input);
                case "AskUserQuestion" -> dispatch_unsupported_phase3(
                        tool_name, "AskUserQuestion requires interactive user I/O");

                    // Task registry family.
                case "TaskCreate" -> dispatch_task_create(input);
                case "RunTaskPacket" -> dispatch_run_task_packet(input);
                case "TaskGet" -> dispatch_task_get(input);
                case "TaskList" -> dispatch_task_list();
                case "TaskStop" -> dispatch_task_stop(input);
                case "TaskUpdate" -> dispatch_task_update(input);
                case "TaskOutput" -> dispatch_task_output(input);

                    // Worker registry — wired through optional WorkerRegistry transport.
                case "WorkerCreate" -> dispatch_worker_create(input);
                case "WorkerGet" -> dispatch_worker_get(input);
                case "WorkerObserve" -> dispatch_worker_observe(input);
                case "WorkerResolveTrust" -> dispatch_worker_resolve_trust(input);
                case "WorkerAwaitReady" -> dispatch_worker_await_ready(input);
                case "WorkerSendPrompt" -> dispatch_worker_send_prompt(input);
                case "WorkerRestart" -> dispatch_worker_restart(input);
                case "WorkerTerminate" -> dispatch_worker_terminate(input);
                case "WorkerObserveCompletion" -> dispatch_worker_observe_completion(input);

                    // Team / Cron family.
                case "TeamCreate" -> dispatch_team_create(input);
                case "TeamDelete" -> dispatch_team_delete(input);
                case "CronCreate" -> dispatch_cron_create(input);
                case "CronDelete" -> dispatch_cron_delete(input);
                case "CronList" -> dispatch_cron_list();

                    // LSP.
                case "LSP" -> dispatch_lsp(input);

                    // MCP family — wired through optional McpToolBridgeAdapter.
                case "ListMcpResources" -> dispatch_list_mcp_resources(input);
                case "ReadMcpResource" -> dispatch_read_mcp_resource(input);
                case "McpAuth" -> dispatch_unsupported_phase3(tool_name, "MCP authentication not ported in Phase 3");
                case "RemoteTrigger" -> dispatch_remote_trigger(input);
                case "MCP" -> dispatch_mcp(input);

                    // Test-only tool — preserved as a stable stub.
                case "TestingPermission" -> dispatch_testing_permission(input);

                    // Harness-side deferred tools — stubbed for surface parity.
                case "EnterWorktree", "ExitWorktree", "Monitor" -> dispatch_unsupported_phase3(
                        tool_name, "harness-side tool deferred to Phase 4");
                case "mcp__claude_ai_Gmail__authenticate",
                        "mcp__claude_ai_Gmail__complete_authentication",
                        "mcp__claude_ai_Google_Calendar__authenticate",
                        "mcp__claude_ai_Google_Calendar__complete_authentication",
                        "mcp__claude_ai_Google_Drive__authenticate",
                        "mcp__claude_ai_Google_Drive__complete_authentication" -> dispatch_unsupported_phase3(
                        tool_name, "MCP claude.ai auth bridge deferred to Phase 4");

                default -> dispatch_plugin_or_unsupported(tool_name, input);
            };
        } catch (UnsupportedToolException e) {
            throw e;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "tool '" + tool_name + "' raised an exception", e);
            return ToolResult.error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    // ----- Phase 1 dispatch arms (unchanged) -----

    private ToolResult dispatch_read_file(JsonNode input) throws Exception {
        String path = required_string(input, "path", "read_file");
        Optional<Integer> offset = optional_int(input, "offset");
        Optional<Integer> limit = optional_int(input, "limit");
        ReadFile.Output output = ReadFile.execute(new ReadFile.Input(path, offset, limit, workspace_root));
        return ToolResult.text(MAPPER.writeValueAsString(output));
    }

    private ToolResult dispatch_write_file(JsonNode input) throws Exception {
        String path = required_string(input, "path", "write_file");
        String content = required_string(input, "content", "write_file");
        WriteFile.Output output = WriteFile.execute(new WriteFile.Input(path, content, workspace_root));
        return ToolResult.text(MAPPER.writeValueAsString(output));
    }

    private ToolResult dispatch_edit_file(JsonNode input) throws Exception {
        String path = required_string(input, "path", "edit_file");
        String old_string = required_string(input, "old_string", "edit_file");
        String new_string = required_string(input, "new_string", "edit_file");
        boolean replace_all =
                input.hasNonNull("replace_all") && input.get("replace_all").asBoolean(false);
        EditFile.Output output =
                EditFile.execute(new EditFile.Input(path, old_string, new_string, replace_all, workspace_root));
        return ToolResult.text(MAPPER.writeValueAsString(output));
    }

    private ToolResult dispatch_glob_search(JsonNode input) throws Exception {
        String pattern = required_string(input, "pattern", "glob_search");
        Optional<String> path = optional_string(input, "path");
        GlobSearch.Output output = GlobSearch.execute(new GlobSearch.Input(pattern, path, workspace_root));
        return ToolResult.text(MAPPER.writeValueAsString(output));
    }

    private ToolResult dispatch_grep_search(JsonNode input) throws Exception {
        String pattern = required_string(input, "pattern", "grep_search");
        String path = input.hasNonNull("path") ? input.get("path").asText() : workspace_root.toString();
        Optional<String> output_mode = optional_string(input, "output_mode");
        GrepSearch.Output output = GrepSearch.execute(new GrepSearch.Input(pattern, path, output_mode, workspace_root));
        return ToolResult.text(MAPPER.writeValueAsString(output));
    }

    private ToolResult dispatch_bash(JsonNode input) throws Exception {
        String command = required_string(input, "command", "bash");
        long timeout_ms = BashCommandInput.DEFAULT_TIMEOUT_MS;
        if (input.hasNonNull("timeout")) {
            timeout_ms = input.get("timeout").asLong(BashCommandInput.DEFAULT_TIMEOUT_MS);
        } else if (input.hasNonNull("timeout_ms")) {
            timeout_ms = input.get("timeout_ms").asLong(BashCommandInput.DEFAULT_TIMEOUT_MS);
        }
        boolean background = input.hasNonNull("run_in_background")
                && input.get("run_in_background").asBoolean(false);
        BashCommandInput bashInput = new BashCommandInput(command, workspace_root, timeout_ms, background);
        BashCommandOutput output = Bash.execute(bashInput);
        boolean is_error = output.exit_code() != 0 || output.timed_out();
        return new ToolResult(MAPPER.writeValueAsString(output), is_error);
    }

    private ToolResult dispatch_todo_write(JsonNode input) throws Exception {
        if (!input.hasNonNull("todos") || !input.get("todos").isArray()) {
            throw new IllegalArgumentException("TodoWrite requires a 'todos' array");
        }
        JsonNode todosNode = input.get("todos");
        List<Map<String, Object>> todos = new ArrayList<>(todosNode.size());
        for (JsonNode item : todosNode) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = MAPPER.convertValue(item, Map.class);
            todos.add(map);
        }
        List<Map<String, Object>> old_todos = todo_store.write(todos);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("old_todos", old_todos);
        response.put("new_todos", todos);
        return ToolResult.text(MAPPER.writeValueAsString(response));
    }

    private ToolResult dispatch_sleep(JsonNode input) throws Exception {
        if (!input.hasNonNull("duration_ms")) {
            throw new IllegalArgumentException("Sleep requires 'duration_ms'");
        }
        long duration_ms = input.get("duration_ms").asLong();
        if (duration_ms < 0) {
            throw new IllegalArgumentException("duration_ms must not be negative");
        }
        if (duration_ms > MAX_SLEEP_DURATION_MS) {
            throw new IllegalArgumentException(
                    "duration_ms " + duration_ms + " exceeds maximum allowed sleep of " + MAX_SLEEP_DURATION_MS + "ms");
        }
        Thread.sleep(duration_ms);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("slept_ms", duration_ms);
        return ToolResult.text(MAPPER.writeValueAsString(payload));
    }

    private ToolResult dispatch_tool_search(JsonNode input) throws Exception {
        String query = required_string(input, "query", "ToolSearch").toLowerCase(Locale.ROOT);
        int max_results = input.hasNonNull("max_results")
                ? Math.max(1, input.get("max_results").asInt(5))
                : 5;
        List<ToolSpec> matches = new ArrayList<>();
        for (ToolSpec spec : registry.specs()) {
            if (spec.name().toLowerCase(Locale.ROOT).contains(query)
                    || (spec.description() != null
                            && spec.description().toLowerCase(Locale.ROOT).contains(query))) {
                matches.add(spec);
                if (matches.size() >= max_results) {
                    break;
                }
            }
        }
        return ToolResult.text(MAPPER.writeValueAsString(new ToolSearchOutput(matches)));
    }

    private ToolResult dispatch_structured_output(JsonNode input) throws Exception {
        return ToolResult.text(MAPPER.writeValueAsString(input));
    }

    private ToolResult dispatch_plan_mode(boolean enter) throws Exception {
        plan_mode.set_plan_mode(enter);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("plan_mode", enter);
        return ToolResult.text(MAPPER.writeValueAsString(payload));
    }

    private ToolResult dispatch_send_user_message(JsonNode input) throws Exception {
        String message = required_string(input, "message", "SendUserMessage");
        user_message_sink.println(message);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("message", message);
        return ToolResult.text(MAPPER.writeValueAsString(payload));
    }

    private ToolResult dispatch_plugin_or_unsupported(String tool_name, JsonNode input) throws Exception {
        // Dynamically-discovered MCP tools surface under mcp__<server>__<tool>; route them through
        // the bridge before falling through to plugins / unsupported.
        if (tool_name.startsWith("mcp__") && mcp_bridge.isPresent()) {
            McpToolBridgeAdapter adapter = mcp_bridge.get();
            if (adapter.handles(tool_name)) {
                return adapter.call_qualified(tool_name, input);
            }
        }
        Optional<PluginTool> match = plugins.find(tool_name);
        if (match.isEmpty()) {
            throw new UnsupportedToolException(tool_name);
        }
        String input_json = MAPPER.writeValueAsString(input);
        String output = PluginToolExecutor.execute(match.get(), input_json);
        return ToolResult.text(output);
    }

    // ----- Phase 3 dispatch arms -----

    private ToolResult dispatch_web_fetch(JsonNode input) throws Exception {
        String url = required_string(input, "url", "WebFetch");
        String prompt = required_string(input, "prompt", "WebFetch");
        String normalized = normalize_fetch_url(url);
        long started = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(normalized))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "jclaude-tools/0.1")
                    .GET()
                    .build();
            HttpResponse<String> response = http_client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            String content_type = response.headers().firstValue("content-type").orElse("");
            String summary = "Fetched " + response.uri() + " (" + body.length() + " bytes) for prompt: " + prompt;
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("url", response.uri().toString());
            payload.put("code", response.statusCode());
            payload.put("content_type", content_type);
            payload.put("bytes", body.length());
            payload.put("result", summary);
            payload.put("duration_ms", System.currentTimeMillis() - started);
            payload.put(
                    "body",
                    body.length() > REMOTE_TRIGGER_MAX_BODY ? body.substring(0, REMOTE_TRIGGER_MAX_BODY) : body);
            return ToolResult.text(MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            ObjectNode err = MAPPER.createObjectNode();
            err.put("url", normalized);
            err.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            err.put("duration_ms", System.currentTimeMillis() - started);
            return ToolResult.error(MAPPER.writeValueAsString(err));
        }
    }

    private ToolResult dispatch_web_search(JsonNode input) throws Exception {
        String query = required_string(input, "query", "WebSearch");
        // Phase 3 stub: report a deterministic empty-result payload mirroring Rust's
        // WebSearchOutput structure when no real search backend is wired in.
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("query", query);
        payload.put("duration_seconds", 0.0);
        payload.putArray("results");
        payload.put("status", "no_backend");
        payload.put(
                "message",
                "WebSearch backend is not configured in Phase 3; returning empty results for query: " + query);
        return ToolResult.text(MAPPER.writeValueAsString(payload));
    }

    private ToolResult dispatch_skill(JsonNode input) throws Exception {
        String skill = required_string(input, "skill", "Skill");
        Optional<String> args = optional_string(input, "args");
        try {
            Path resolved = SkillsHandler.resolve_skill_path(workspace_root, skill);
            String prompt = Files.readString(resolved);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("skill", skill);
            payload.put("path", resolved.toString());
            args.ifPresentOrElse(value -> payload.put("args", value), () -> payload.putNull("args"));
            payload.put("prompt", prompt);
            payload.put("description", parse_skill_description(prompt));
            return ToolResult.text(MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            return ToolResult.error(
                    "Skill resolution failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private ToolResult dispatch_agent(JsonNode input) throws Exception {
        // Phase 3 stub: validate the input shape and report a manifest-shaped payload without
        // spawning a sub-agent thread (the agent runtime is deferred to a later phase).
        String description = required_string(input, "description", "Agent");
        String prompt = required_string(input, "prompt", "Agent");
        if (description.isBlank()) {
            return ToolResult.error("description must not be empty");
        }
        if (prompt.isBlank()) {
            return ToolResult.error("prompt must not be empty");
        }
        Optional<String> subagent_type = optional_string(input, "subagent_type");
        Optional<String> name = optional_string(input, "name");
        Optional<String> model = optional_string(input, "model");
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("agent_id", "agent_phase3_stub");
        payload.put("name", name.orElse(slugify(description)));
        payload.put("description", description);
        payload.put("subagent_type", subagent_type.orElse("default"));
        payload.put("model", model.orElse("inherit"));
        payload.put("status", "pending");
        payload.put("derived_state", "queued");
        payload.put("message", "Agent runtime not ported in Phase 3; manifest persisted in-memory only");
        payload.put("error", NOT_YET_IMPLEMENTED);
        return ToolResult.text(MAPPER.writeValueAsString(payload));
    }

    private ToolResult dispatch_notebook_edit(JsonNode input) throws Exception {
        String notebook_path = required_string(input, "notebook_path", "NotebookEdit");
        if (!notebook_path.endsWith(".ipynb")) {
            return ToolResult.error("File must be a Jupyter notebook (.ipynb file).");
        }
        Path resolved = workspace_root.resolve(notebook_path).normalize();
        if (!Files.exists(resolved)) {
            return ToolResult.error("Notebook not found: " + notebook_path);
        }
        String original = Files.readString(resolved);
        JsonNode notebook = MAPPER.readTree(original);
        if (!notebook.isObject()) {
            return ToolResult.error("Notebook root is not a JSON object");
        }
        ObjectNode root = (ObjectNode) notebook;
        if (!root.has("cells") || !root.get("cells").isArray()) {
            return ToolResult.error("Notebook cells array not found");
        }
        ArrayNode cells = (ArrayNode) root.get("cells");
        String edit_mode =
                input.hasNonNull("edit_mode") ? input.get("edit_mode").asText() : "replace";
        Optional<String> cell_id = optional_string(input, "cell_id");
        Optional<String> cell_type = optional_string(input, "cell_type");
        Optional<String> new_source = optional_string(input, "new_source");

        int target_index = -1;
        if (cell_id.isPresent()) {
            for (int i = 0; i < cells.size(); i++) {
                JsonNode c = cells.get(i);
                if (c.has("id") && c.get("id").asText().equals(cell_id.get())) {
                    target_index = i;
                    break;
                }
            }
            if (target_index < 0 && !"insert".equals(edit_mode)) {
                return ToolResult.error("cell_id not found: " + cell_id.get());
            }
        } else if ("replace".equals(edit_mode) || "delete".equals(edit_mode)) {
            if (cells.isEmpty()) {
                return ToolResult.error(edit_mode + " mode requires at least one cell");
            }
            target_index = 0;
        }

        String resolved_cell_id;
        switch (edit_mode) {
            case "insert" -> {
                if (new_source.isEmpty()) {
                    return ToolResult.error("new_source is required for insert mode");
                }
                String new_id = "cell-" + cells.size();
                ObjectNode new_cell = MAPPER.createObjectNode();
                String resolved_type = cell_type.orElse("code");
                new_cell.put("cell_type", resolved_type);
                new_cell.put("id", new_id);
                new_cell.set("metadata", MAPPER.createObjectNode());
                new_cell.set("source", to_source_lines(new_source.get()));
                if ("code".equals(resolved_type)) {
                    new_cell.set("outputs", MAPPER.createArrayNode());
                    new_cell.putNull("execution_count");
                }
                int insert_at = target_index < 0 ? cells.size() : target_index + 1;
                cells.insert(insert_at, new_cell);
                resolved_cell_id = new_id;
            }
            case "delete" -> {
                JsonNode removed = cells.remove(target_index);
                resolved_cell_id = removed.has("id") ? removed.get("id").asText() : null;
            }
            default -> {
                if (new_source.isEmpty()) {
                    return ToolResult.error("new_source is required for replace mode");
                }
                ObjectNode cell = (ObjectNode) cells.get(target_index);
                cell.set("source", to_source_lines(new_source.get()));
                String resolved_type = cell_type.orElseGet(
                        () -> cell.has("cell_type") ? cell.get("cell_type").asText() : "code");
                cell.put("cell_type", resolved_type);
                resolved_cell_id = cell.has("id") ? cell.get("id").asText() : null;
            }
        }

        String updated = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(notebook);
        Files.writeString(resolved, updated);

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("notebook_path", resolved.toString());
        payload.put("edit_mode", edit_mode);
        if (resolved_cell_id != null) {
            payload.put("cell_id", resolved_cell_id);
        } else {
            payload.putNull("cell_id");
        }
        cell_type.ifPresentOrElse(value -> payload.put("cell_type", value), () -> payload.putNull("cell_type"));
        new_source.ifPresentOrElse(value -> payload.put("new_source", value), () -> payload.putNull("new_source"));
        payload.put("language", "python");
        payload.put("original_file", original);
        payload.put("updated_file", updated);
        payload.putNull("error");
        return ToolResult.text(MAPPER.writeValueAsString(payload));
    }

    private ToolResult dispatch_config(JsonNode input) throws Exception {
        String setting = required_string(input, "setting", "Config");
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("setting", setting);
        if (input.hasNonNull("value")) {
            payload.put("operation", "set");
            payload.set("value", input.get("value"));
        } else {
            payload.put("operation", "get");
            payload.putNull("value");
        }
        // Phase 3 stub: surface the structured response shape but never persist values to disk.
        payload.put("success", true);
        payload.put("message", "Config inspection is read-only in Phase 3; settings are not persisted");
        return ToolResult.text(MAPPER.writeValueAsString(payload));
    }

    private ToolResult dispatch_repl(JsonNode input) throws Exception {
        String code = required_string(input, "code", "REPL");
        String language = required_string(input, "language", "REPL");
        if (code.isBlank()) {
            return ToolResult.error("code must not be empty");
        }
        long timeout_ms = input.hasNonNull("timeout_ms")
                ? Math.max(1L, input.get("timeout_ms").asLong())
                : BashCommandInput.DEFAULT_TIMEOUT_MS;
        String shell_command = build_repl_command(language, code);
        if (shell_command == null) {
            return ToolResult.error("unsupported REPL language: " + language);
        }
        long started = System.currentTimeMillis();
        BashCommandInput bashInput = new BashCommandInput(shell_command, workspace_root, timeout_ms, false);
        BashCommandOutput output = Bash.execute(bashInput);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("language", language);
        payload.put("stdout", output.stdout());
        payload.put("stderr", output.stderr());
        payload.put("exit_code", output.exit_code());
        payload.put("duration_ms", System.currentTimeMillis() - started);
        return new ToolResult(MAPPER.writeValueAsString(payload), output.exit_code() != 0 || output.timed_out());
    }

    private ToolResult dispatch_powershell(JsonNode input) throws Exception {
        String command = required_string(input, "command", "PowerShell");
        // PowerShell is Windows-specific. On non-Windows hosts, surface the canonical "not yet
        // implemented" error so callers can branch on it.
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("windows")) {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("command", command);
            payload.put("error", NOT_YET_IMPLEMENTED);
            payload.put("status", "unsupported");
            payload.put("message", "PowerShell is only supported on Windows hosts");
            return ToolResult.error(MAPPER.writeValueAsString(payload));
        }
        long timeout_ms = input.hasNonNull("timeout")
                ? Math.max(1L, input.get("timeout").asLong())
                : BashCommandInput.DEFAULT_TIMEOUT_MS;
        long started = System.currentTimeMillis();
        BashCommandInput bashInput = new BashCommandInput(
                "powershell -NoProfile -Command \"" + command.replace("\"", "\\\"") + "\"",
                workspace_root,
                timeout_ms,
                false);
        BashCommandOutput output = Bash.execute(bashInput);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("stdout", output.stdout());
        payload.put("stderr", output.stderr());
        payload.put("exit_code", output.exit_code());
        payload.put("duration_ms", System.currentTimeMillis() - started);
        return new ToolResult(MAPPER.writeValueAsString(payload), output.exit_code() != 0 || output.timed_out());
    }

    // ----- Task family -----

    private ToolResult dispatch_task_create(JsonNode input) throws Exception {
        String prompt = required_string(input, "prompt", "TaskCreate");
        String description = optional_string(input, "description").orElse(null);
        Task task = task_registry.create(prompt, description);
        return ToolResult.text(MAPPER.writeValueAsString(task));
    }

    private ToolResult dispatch_run_task_packet(JsonNode input) throws Exception {
        try {
            TaskPacket packet = packet_from_json(input);
            Task task = task_registry.create_from_packet(packet);
            return ToolResult.text(MAPPER.writeValueAsString(task));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private ToolResult dispatch_task_get(JsonNode input) throws Exception {
        String task_id = required_string(input, "task_id", "TaskGet");
        Optional<Task> task = task_registry.get(task_id);
        if (task.isEmpty()) {
            return ToolResult.error("task not found: " + task_id);
        }
        return ToolResult.text(MAPPER.writeValueAsString(task.get()));
    }

    private ToolResult dispatch_task_list() throws Exception {
        List<Task> tasks = task_registry.list(Optional.empty());
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode array = payload.putArray("tasks");
        for (Task task : tasks) {
            array.add(MAPPER.valueToTree(task));
        }
        payload.put("count", tasks.size());
        return ToolResult.text(MAPPER.writeValueAsString(payload));
    }

    private ToolResult dispatch_task_stop(JsonNode input) throws Exception {
        String task_id = required_string(input, "task_id", "TaskStop");
        try {
            Task stopped = task_registry.stop(task_id);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("task_id", stopped.task_id());
            payload.put("status", stopped.status().wire());
            payload.put("message", "Task stopped");
            return ToolResult.text(MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private ToolResult dispatch_task_update(JsonNode input) throws Exception {
        String task_id = required_string(input, "task_id", "TaskUpdate");
        String message = required_string(input, "message", "TaskUpdate");
        try {
            Task updated = task_registry.update(task_id, message);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("task_id", updated.task_id());
            payload.put("status", updated.status().wire());
            payload.put("message_count", updated.messages().size());
            payload.put("last_message", message);
            return ToolResult.text(MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private ToolResult dispatch_task_output(JsonNode input) throws Exception {
        String task_id = required_string(input, "task_id", "TaskOutput");
        try {
            String output = task_registry.output(task_id);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("task_id", task_id);
            payload.put("output", output);
            payload.put("has_output", !output.isEmpty());
            return ToolResult.text(MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    // ----- Team / Cron family -----

    private ToolResult dispatch_team_create(JsonNode input) throws Exception {
        String name = required_string(input, "name", "TeamCreate");
        if (!input.hasNonNull("tasks") || !input.get("tasks").isArray()) {
            throw new IllegalArgumentException("TeamCreate requires a 'tasks' array");
        }
        List<String> task_ids = new ArrayList<>();
        for (JsonNode item : input.get("tasks")) {
            if (item.hasNonNull("task_id")) {
                task_ids.add(item.get("task_id").asText());
            }
        }
        Team team = team_registry.create(name, task_ids);
        for (String task_id : team.task_ids()) {
            try {
                task_registry.assign_team(task_id, team.team_id());
            } catch (Exception ignore) {
                // Tasks may not exist yet — best-effort link, mirrors Rust.
            }
        }
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("team_id", team.team_id());
        payload.put("name", team.name());
        payload.put("task_count", team.task_ids().size());
        ArrayNode ids = payload.putArray("task_ids");
        team.task_ids().forEach(ids::add);
        payload.put("status", team.status().wire());
        payload.put("created_at", team.created_at());
        return ToolResult.text(MAPPER.writeValueAsString(payload));
    }

    private ToolResult dispatch_team_delete(JsonNode input) throws Exception {
        String team_id = required_string(input, "team_id", "TeamDelete");
        try {
            Team deleted = team_registry.delete(team_id);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("team_id", deleted.team_id());
            payload.put("name", deleted.name());
            payload.put("status", deleted.status().wire());
            payload.put("message", "Team deleted");
            return ToolResult.text(MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private ToolResult dispatch_cron_create(JsonNode input) throws Exception {
        String schedule = required_string(input, "schedule", "CronCreate");
        String prompt = required_string(input, "prompt", "CronCreate");
        String description = optional_string(input, "description").orElse(null);
        CronEntry entry = cron_registry.create(schedule, prompt, description);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("cron_id", entry.cron_id());
        payload.put("schedule", entry.schedule());
        payload.put("prompt", entry.prompt());
        if (entry.description().isPresent()) {
            payload.put("description", entry.description().get());
        } else {
            payload.putNull("description");
        }
        payload.put("enabled", entry.enabled());
        payload.put("created_at", entry.created_at());
        return ToolResult.text(MAPPER.writeValueAsString(payload));
    }

    private ToolResult dispatch_cron_delete(JsonNode input) throws Exception {
        String cron_id = required_string(input, "cron_id", "CronDelete");
        try {
            CronEntry deleted = cron_registry.delete(cron_id);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("cron_id", deleted.cron_id());
            payload.put("schedule", deleted.schedule());
            payload.put("status", "deleted");
            payload.put("message", "Cron entry removed");
            return ToolResult.text(MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private ToolResult dispatch_cron_list() throws Exception {
        List<CronEntry> entries = cron_registry.list(false);
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode array = payload.putArray("crons");
        for (CronEntry entry : entries) {
            array.add(MAPPER.valueToTree(entry));
        }
        payload.put("count", entries.size());
        return ToolResult.text(MAPPER.writeValueAsString(payload));
    }

    // ----- LSP -----

    private ToolResult dispatch_lsp(JsonNode input) throws Exception {
        String action = required_string(input, "action", "LSP");
        String path = optional_string(input, "path").orElse(null);
        Integer line = input.hasNonNull("line") ? input.get("line").asInt() : null;
        Integer character =
                input.hasNonNull("character") ? input.get("character").asInt() : null;
        String query = optional_string(input, "query").orElse(null);

        // If a real LspClient is configured for the resolved language, prefer it; otherwise fall
        // through to the state-only registry path.
        Optional<LspClient> client = resolve_lsp_client(path);
        if (client.isPresent()) {
            try {
                Optional<JsonNode> live = dispatch_lsp_via_client(client.get(), action, path, line, character);
                if (live.isPresent()) {
                    return ToolResult.text(MAPPER.writeValueAsString(live.get()));
                }
                // Fall through if the action isn't handled by the client wrapper (e.g. diagnostics).
            } catch (Exception e) {
                ObjectNode payload = MAPPER.createObjectNode();
                payload.put("action", action);
                payload.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
                payload.put("status", "error");
                return ToolResult.text(MAPPER.writeValueAsString(payload));
            }
        }

        try {
            JsonNode response = lsp_registry.dispatch(action, path, line, character, query);
            return ToolResult.text(MAPPER.writeValueAsString(response));
        } catch (Exception e) {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("action", action);
            payload.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            payload.put("status", "error");
            return ToolResult.text(MAPPER.writeValueAsString(payload));
        }
    }

    private Optional<LspClient> resolve_lsp_client(String path) {
        if (lsp_clients_by_language.isEmpty() || path == null) {
            return Optional.empty();
        }
        String language = lsp_language_for_path(path);
        if (language == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(lsp_clients_by_language.get().get(language));
    }

    private Optional<JsonNode> dispatch_lsp_via_client(
            LspClient client, String action, String path, Integer line, Integer character) throws Exception {
        Optional<LspAction> resolved_action = LspAction.from_str(action);
        if (resolved_action.isEmpty()) {
            return Optional.empty();
        }
        String uri = path == null
                ? null
                : (path.startsWith("file://")
                        ? path
                        : workspace_root.resolve(path).toUri().toString());
        Duration timeout = Duration.ofSeconds(20);
        JsonNode result =
                switch (resolved_action.get()) {
                    case HOVER -> client.hover(uri, line == null ? 0 : line, character == null ? 0 : character)
                            .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    case DEFINITION -> client.definition(
                                    uri, line == null ? 0 : line, character == null ? 0 : character)
                            .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    case REFERENCES -> client.references(
                                    uri, line == null ? 0 : line, character == null ? 0 : character)
                            .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    case COMPLETION -> client.completion(
                                    uri, line == null ? 0 : line, character == null ? 0 : character)
                            .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    case SYMBOLS -> client.document_symbols(uri).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    case FORMAT -> client.formatting(uri).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    case DIAGNOSTICS -> null;
                };
        if (result == null) {
            return Optional.empty();
        }
        ObjectNode response = MAPPER.createObjectNode();
        response.put("action", action);
        response.put("path", path);
        if (line != null) {
            response.put("line", line);
        } else {
            response.putNull("line");
        }
        if (character != null) {
            response.put("character", character);
        } else {
            response.putNull("character");
        }
        response.put("status", "live");
        response.set("result", result);
        return Optional.of(response);
    }

    private static String lsp_language_for_path(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return null;
        }
        return switch (path.substring(dot + 1)) {
            case "rs" -> "rust";
            case "ts", "tsx" -> "typescript";
            case "js", "jsx" -> "javascript";
            case "py" -> "python";
            case "go" -> "go";
            case "java" -> "java";
            case "c", "h" -> "c";
            case "cpp", "hpp", "cc" -> "cpp";
            case "rb" -> "ruby";
            case "lua" -> "lua";
            default -> null;
        };
    }

    // ----- MCP family -----

    private ToolResult dispatch_list_mcp_resources(JsonNode input) throws Exception {
        String server = optional_string(input, "server").orElse("default");
        if (mcp_bridge.isEmpty()) {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("server", server);
            payload.putArray("resources");
            payload.put("count", 0);
            payload.put("error", NOT_YET_IMPLEMENTED);
            payload.put("message", "MCP server registry not ported in Phase 3");
            return ToolResult.text(MAPPER.writeValueAsString(payload));
        }
        try {
            List<McpToolBridge.McpResourceInfo> resources = mcp_bridge.get().list_resources(server);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("server", server);
            ArrayNode array = payload.putArray("resources");
            for (McpToolBridge.McpResourceInfo r : resources) {
                ObjectNode entry = array.addObject();
                entry.put("uri", r.uri());
                entry.put("name", r.name());
                r.description().ifPresentOrElse(d -> entry.put("description", d), () -> entry.putNull("description"));
                r.mime_type().ifPresentOrElse(m -> entry.put("mime_type", m), () -> entry.putNull("mime_type"));
            }
            payload.put("count", resources.size());
            return ToolResult.text(MAPPER.writeValueAsString(payload));
        } catch (RuntimeException error) {
            return ToolResult.error(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private ToolResult dispatch_read_mcp_resource(JsonNode input) throws Exception {
        String uri = required_string(input, "uri", "ReadMcpResource");
        String server = optional_string(input, "server").orElse("default");
        if (mcp_bridge.isEmpty()) {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("server", server);
            payload.put("uri", uri);
            payload.put("error", NOT_YET_IMPLEMENTED);
            payload.put("message", "MCP server registry not ported in Phase 3");
            return ToolResult.text(MAPPER.writeValueAsString(payload));
        }
        try {
            McpToolBridge.McpResourceInfo resource = mcp_bridge.get().read_resource(server, uri);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("server", server);
            payload.put("uri", resource.uri());
            payload.put("name", resource.name());
            resource.description()
                    .ifPresentOrElse(d -> payload.put("description", d), () -> payload.putNull("description"));
            resource.mime_type().ifPresentOrElse(m -> payload.put("mime_type", m), () -> payload.putNull("mime_type"));
            return ToolResult.text(MAPPER.writeValueAsString(payload));
        } catch (RuntimeException error) {
            return ToolResult.error(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private ToolResult dispatch_mcp(JsonNode input) throws Exception {
        String server = required_string(input, "server", "MCP");
        String tool = required_string(input, "tool", "MCP");
        if (mcp_bridge.isEmpty()) {
            return dispatch_unsupported_phase3("MCP", "MCP tool invocation bridge not ported in Phase 3");
        }
        JsonNode arguments = input.hasNonNull("arguments") ? input.get("arguments") : null;
        return mcp_bridge.get().call_structured(server, tool, arguments);
    }

    // ----- Worker family -----

    private ToolResult dispatch_worker_create(JsonNode input) throws Exception {
        if (worker_registry.isEmpty()) {
            return dispatch_unsupported_phase3("WorkerCreate", "Worker registry not ported in Phase 3");
        }
        String cwd = required_string(input, "cwd", "WorkerCreate");
        List<String> trusted_roots = string_list(input, "trusted_roots");
        boolean auto_recover = input.hasNonNull("auto_recover_prompt_misdelivery")
                && input.get("auto_recover_prompt_misdelivery").asBoolean(false);
        Worker worker = worker_registry.get().create(cwd, trusted_roots, auto_recover);
        return ToolResult.text(MAPPER.writeValueAsString(worker_payload(worker)));
    }

    private ToolResult dispatch_worker_get(JsonNode input) throws Exception {
        if (worker_registry.isEmpty()) {
            return dispatch_unsupported_phase3("WorkerGet", "Worker registry not ported in Phase 3");
        }
        String worker_id = required_string(input, "worker_id", "WorkerGet");
        Optional<Worker> worker = worker_registry.get().get(worker_id);
        if (worker.isEmpty()) {
            return ToolResult.error("worker not found: " + worker_id);
        }
        return ToolResult.text(MAPPER.writeValueAsString(worker_payload(worker.get())));
    }

    private ToolResult dispatch_worker_observe(JsonNode input) throws Exception {
        if (worker_registry.isEmpty()) {
            return dispatch_unsupported_phase3("WorkerObserve", "Worker registry not ported in Phase 3");
        }
        String worker_id = required_string(input, "worker_id", "WorkerObserve");
        String screen_text = required_string(input, "screen_text", "WorkerObserve");
        try {
            Worker worker = worker_registry.get().observe(worker_id, screen_text);
            return ToolResult.text(MAPPER.writeValueAsString(worker_payload(worker)));
        } catch (RuntimeException error) {
            return ToolResult.error(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private ToolResult dispatch_worker_resolve_trust(JsonNode input) throws Exception {
        if (worker_registry.isEmpty()) {
            return dispatch_unsupported_phase3("WorkerResolveTrust", "Worker registry not ported in Phase 3");
        }
        String worker_id = required_string(input, "worker_id", "WorkerResolveTrust");
        try {
            Worker worker = worker_registry.get().resolve_trust(worker_id);
            return ToolResult.text(MAPPER.writeValueAsString(worker_payload(worker)));
        } catch (RuntimeException error) {
            return ToolResult.error(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private ToolResult dispatch_worker_await_ready(JsonNode input) throws Exception {
        if (worker_registry.isEmpty()) {
            return dispatch_unsupported_phase3("WorkerAwaitReady", "Worker registry not ported in Phase 3");
        }
        String worker_id = required_string(input, "worker_id", "WorkerAwaitReady");
        try {
            WorkerReadySnapshot snapshot = worker_registry.get().await_ready(worker_id);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("worker_id", snapshot.worker_id());
            payload.put("status", snapshot.status().display());
            payload.put("ready", snapshot.ready());
            payload.put("blocked", snapshot.blocked());
            payload.put("has_replay", snapshot.replay_prompt_ready());
            return ToolResult.text(MAPPER.writeValueAsString(payload));
        } catch (RuntimeException error) {
            return ToolResult.error(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private ToolResult dispatch_worker_send_prompt(JsonNode input) throws Exception {
        if (worker_registry.isEmpty()) {
            return dispatch_unsupported_phase3("WorkerSendPrompt", "Worker registry not ported in Phase 3");
        }
        String worker_id = required_string(input, "worker_id", "WorkerSendPrompt");
        String prompt = optional_string(input, "prompt").orElse(null);
        WorkerTaskReceipt receipt = parse_task_receipt(input.get("task_receipt"));
        try {
            Worker worker = worker_registry.get().send_prompt(worker_id, prompt, receipt);
            return ToolResult.text(MAPPER.writeValueAsString(worker_payload(worker)));
        } catch (RuntimeException error) {
            return ToolResult.error(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private ToolResult dispatch_worker_restart(JsonNode input) throws Exception {
        if (worker_registry.isEmpty()) {
            return dispatch_unsupported_phase3("WorkerRestart", "Worker registry not ported in Phase 3");
        }
        String worker_id = required_string(input, "worker_id", "WorkerRestart");
        try {
            Worker worker = worker_registry.get().restart(worker_id);
            return ToolResult.text(MAPPER.writeValueAsString(worker_payload(worker)));
        } catch (RuntimeException error) {
            return ToolResult.error(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private ToolResult dispatch_worker_terminate(JsonNode input) throws Exception {
        if (worker_registry.isEmpty()) {
            return dispatch_unsupported_phase3("WorkerTerminate", "Worker registry not ported in Phase 3");
        }
        String worker_id = required_string(input, "worker_id", "WorkerTerminate");
        try {
            Worker worker = worker_registry.get().terminate(worker_id);
            return ToolResult.text(MAPPER.writeValueAsString(worker_payload(worker)));
        } catch (RuntimeException error) {
            return ToolResult.error(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private ToolResult dispatch_worker_observe_completion(JsonNode input) throws Exception {
        if (worker_registry.isEmpty()) {
            return dispatch_unsupported_phase3("WorkerObserveCompletion", "Worker registry not ported in Phase 3");
        }
        String worker_id = required_string(input, "worker_id", "WorkerObserveCompletion");
        String finish_reason = required_string(input, "finish_reason", "WorkerObserveCompletion");
        long tokens_output =
                input.hasNonNull("tokens_output") ? input.get("tokens_output").asLong(0L) : 0L;
        try {
            Worker worker = worker_registry.get().observe_completion(worker_id, finish_reason, tokens_output);
            return ToolResult.text(MAPPER.writeValueAsString(worker_payload(worker)));
        } catch (RuntimeException error) {
            return ToolResult.error(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private static WorkerTaskReceipt parse_task_receipt(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String repo = node.hasNonNull("repo") ? node.get("repo").asText() : null;
        String task_kind = node.hasNonNull("task_kind") ? node.get("task_kind").asText() : null;
        String source_surface =
                node.hasNonNull("source_surface") ? node.get("source_surface").asText() : null;
        String objective_preview = node.hasNonNull("objective_preview")
                ? node.get("objective_preview").asText()
                : null;
        if (repo == null || task_kind == null || source_surface == null || objective_preview == null) {
            return null;
        }
        List<String> artifacts = new ArrayList<>();
        if (node.hasNonNull("expected_artifacts")
                && node.get("expected_artifacts").isArray()) {
            for (JsonNode a : node.get("expected_artifacts")) {
                artifacts.add(a.asText());
            }
        }
        return new WorkerTaskReceipt(repo, task_kind, source_surface, artifacts, objective_preview);
    }

    private static List<String> string_list(JsonNode input, String field) {
        List<String> out = new ArrayList<>();
        if (!input.hasNonNull(field) || !input.get(field).isArray()) {
            return out;
        }
        for (JsonNode item : input.get(field)) {
            if (item != null && item.isTextual()) {
                out.add(item.asText());
            }
        }
        return out;
    }

    private ObjectNode worker_payload(Worker worker) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("worker_id", worker.worker_id());
        payload.put("cwd", worker.cwd());
        payload.put("status", worker.status().display());
        payload.put("trust_gate_cleared", worker.trust_gate_cleared());
        payload.put("prompt_in_flight", worker.prompt_in_flight());
        payload.put("prompt_delivery_attempts", worker.prompt_delivery_attempts());
        worker.last_prompt().ifPresentOrElse(p -> payload.put("last_prompt", p), () -> payload.putNull("last_prompt"));
        payload.put("created_at", worker.created_at());
        payload.put("updated_at", worker.updated_at());
        payload.put("event_count", worker.events().size());
        if (worker.last_error().isPresent()) {
            ObjectNode error = payload.putObject("last_error");
            error.put("kind", worker.last_error().get().kind().name());
            error.put("message", worker.last_error().get().message());
            error.put("created_at", worker.last_error().get().created_at());
        } else {
            payload.putNull("last_error");
        }
        return payload;
    }

    private ToolResult dispatch_remote_trigger(JsonNode input) throws Exception {
        String url = required_string(input, "url", "RemoteTrigger");
        String method = optional_string(input, "method").orElse("GET").toUpperCase(Locale.ROOT);
        try {
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(REMOTE_TRIGGER_TIMEOUT_SECONDS));
            String body = input.hasNonNull("body") ? input.get("body").asText() : "";
            HttpRequest.BodyPublisher publisher =
                    body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
            switch (method) {
                case "GET" -> builder.GET();
                case "POST" -> builder.POST(publisher);
                case "PUT" -> builder.PUT(publisher);
                case "DELETE" -> builder.DELETE();
                case "PATCH" -> builder.method("PATCH", publisher);
                case "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
                default -> {
                    return ToolResult.error("unsupported HTTP method: " + method);
                }
            }
            if (input.hasNonNull("headers") && input.get("headers").isObject()) {
                Iterator<Map.Entry<String, JsonNode>> headers =
                        input.get("headers").fields();
                while (headers.hasNext()) {
                    Map.Entry<String, JsonNode> header = headers.next();
                    if (header.getValue() != null && header.getValue().isTextual()) {
                        builder.header(header.getKey(), header.getValue().asText());
                    }
                }
            }
            HttpResponse<String> response = http_client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String response_body = response.body() == null ? "" : response.body();
            String truncated = response_body.length() > REMOTE_TRIGGER_MAX_BODY
                    ? response_body.substring(0, REMOTE_TRIGGER_MAX_BODY)
                            + "\n\n[response truncated — "
                            + response_body.length()
                            + " bytes total]"
                    : response_body;
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("url", url);
            payload.put("method", method);
            payload.put("status_code", response.statusCode());
            payload.put("body", truncated);
            payload.put("success", response.statusCode() >= 200 && response.statusCode() < 300);
            return ToolResult.text(MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("url", url);
            payload.put("method", method);
            payload.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            payload.put("success", false);
            return ToolResult.text(MAPPER.writeValueAsString(payload));
        }
    }

    private ToolResult dispatch_testing_permission(JsonNode input) throws Exception {
        String action = required_string(input, "action", "TestingPermission");
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("action", action);
        payload.put("permitted", true);
        payload.put("message", "Testing permission tool stub");
        return ToolResult.text(MAPPER.writeValueAsString(payload));
    }

    // ----- Stub helpers -----

    private ToolResult dispatch_unsupported_phase3(String tool_name, String reason) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("tool", tool_name);
        payload.put("error", NOT_YET_IMPLEMENTED);
        payload.put("kind", "unsupported");
        payload.put("reason", reason);
        return ToolResult.error(MAPPER.writeValueAsString(payload));
    }

    // ----- Static helpers -----

    private static String build_repl_command(String language, String code) {
        String lang = language.trim().toLowerCase(Locale.ROOT);
        return switch (lang) {
            case "python", "py" -> "python3 -c " + shell_quote(code);
            case "javascript", "js", "node" -> "node -e " + shell_quote(code);
            case "sh", "shell", "bash" -> "bash -lc " + shell_quote(code);
            default -> null;
        };
    }

    private static String shell_quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static ArrayNode to_source_lines(String source) {
        ArrayNode array = MAPPER.createArrayNode();
        if (source.isEmpty()) {
            return array;
        }
        String[] parts = source.split("\\n", -1);
        for (int i = 0; i < parts.length; i++) {
            String suffix = i < parts.length - 1 ? "\n" : "";
            array.add(parts[i] + suffix);
        }
        return array;
    }

    private static String slugify(String value) {
        String trimmed = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        if (trimmed.startsWith("-")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("-")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "agent" : trimmed;
    }

    private static String parse_skill_description(String prompt) {
        // Best-effort skill description parse — looks for a `description:` line in the
        // YAML frontmatter, otherwise returns null.
        String[] lines = prompt.split("\\n", -1);
        boolean in_frontmatter = false;
        for (String line : lines) {
            if (line.equals("---")) {
                if (!in_frontmatter) {
                    in_frontmatter = true;
                    continue;
                }
                break;
            }
            if (!in_frontmatter) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("description:")) {
                String value = trimmed.substring("description:".length()).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    return value.substring(1, value.length() - 1);
                }
                if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
                    return value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }

    private static String normalize_fetch_url(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be empty");
        }
        if (url.startsWith("http://localhost")
                || url.startsWith("http://127.0.0.1")
                || url.startsWith("http://[::1]")) {
            return url;
        }
        if (url.startsWith("http://")) {
            return "https://" + url.substring("http://".length());
        }
        return url;
    }

    private static TaskPacket packet_from_json(JsonNode input) {
        String objective = required_string(input, "objective", "RunTaskPacket");
        String repo = required_string(input, "repo", "RunTaskPacket");
        String branch_policy = required_string(input, "branch_policy", "RunTaskPacket");
        String commit_policy = required_string(input, "commit_policy", "RunTaskPacket");
        String reporting_contract = required_string(input, "reporting_contract", "RunTaskPacket");
        String escalation_policy = required_string(input, "escalation_policy", "RunTaskPacket");
        if (!input.hasNonNull("acceptance_tests")
                || !input.get("acceptance_tests").isArray()) {
            throw new IllegalArgumentException("RunTaskPacket requires 'acceptance_tests' (array)");
        }
        List<String> acceptance_tests = new ArrayList<>();
        for (JsonNode item : input.get("acceptance_tests")) {
            acceptance_tests.add(item.asText());
        }
        TaskScope scope = TaskScope.WORKSPACE;
        if (input.hasNonNull("scope")) {
            try {
                scope = TaskScope.from_wire(input.get("scope").asText());
            } catch (Exception ignore) {
                scope = TaskScope.CUSTOM;
            }
        }
        String scope_path = optional_string(input, "scope_path").orElse(null);
        String worktree = optional_string(input, "worktree").orElse(null);
        return new TaskPacket(
                objective,
                scope,
                scope_path,
                repo,
                worktree,
                branch_policy,
                acceptance_tests,
                commit_policy,
                reporting_contract,
                escalation_policy);
    }

    private static String required_string(JsonNode input, String field, String tool) {
        if (!input.hasNonNull(field) || !input.get(field).isTextual()) {
            throw new IllegalArgumentException(tool + " requires '" + field + "' (string)");
        }
        return input.get(field).asText();
    }

    private static Optional<String> optional_string(JsonNode input, String field) {
        if (!input.hasNonNull(field)) {
            return Optional.empty();
        }
        return Optional.of(input.get(field).asText());
    }

    private static Optional<Integer> optional_int(JsonNode input, String field) {
        if (!input.hasNonNull(field)) {
            return Optional.empty();
        }
        return Optional.of(input.get(field).asInt());
    }
}
