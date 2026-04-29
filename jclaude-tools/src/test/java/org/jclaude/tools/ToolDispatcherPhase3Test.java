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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.jclaude.api.json.JclaudeMappers;
import org.jclaude.runtime.lsp.LspRegistry;
import org.jclaude.runtime.lsp.LspServerStatus;
import org.jclaude.runtime.task.Task;
import org.jclaude.runtime.task.TaskRegistry;
import org.jclaude.runtime.team.CronEntry;
import org.jclaude.runtime.team.CronRegistry;
import org.jclaude.runtime.team.TeamRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolDispatcherPhase3Test {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    private ToolDispatcher dispatcher_for(
            Path workspace, TaskRegistry tasks, TeamRegistry teams, CronRegistry crons, LspRegistry lsp) {
        return new ToolDispatcher(
                workspace,
                new GlobalToolRegistry(MvpToolSpecs.all_tool_specs()),
                new TodoStore(),
                new PlanModeState(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                org.jclaude.plugins.PluginRegistry.empty(),
                tasks,
                teams,
                crons,
                lsp);
    }

    private ToolDispatcher dispatcher_for(Path workspace) {
        return dispatcher_for(workspace, new TaskRegistry(), new TeamRegistry(), new CronRegistry(), new LspRegistry());
    }

    @Test
    void task_create_dispatches_to_runtime_task_registry(@TempDir Path workspace) throws IOException {
        TaskRegistry tasks = new TaskRegistry();
        ToolDispatcher dispatcher =
                dispatcher_for(workspace, tasks, new TeamRegistry(), new CronRegistry(), new LspRegistry());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "do the thing");
        input.put("description", "phase 3 demo");

        ToolResult result = dispatcher.execute("TaskCreate", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("task_id").asText()).startsWith("task_");
        assertThat(payload.get("status").asText()).isEqualTo("created");
        assertThat(payload.get("prompt").asText()).isEqualTo("do the thing");
        assertThat(tasks.len()).isEqualTo(1);
    }

    @Test
    void task_list_returns_registry_snapshot(@TempDir Path workspace) throws IOException {
        TaskRegistry tasks = new TaskRegistry();
        tasks.create("first", null);
        tasks.create("second", "with description");
        ToolDispatcher dispatcher =
                dispatcher_for(workspace, tasks, new TeamRegistry(), new CronRegistry(), new LspRegistry());

        ToolResult result = dispatcher.execute("TaskList", MAPPER.createObjectNode());

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("count").asInt()).isEqualTo(2);
        assertThat(payload.get("tasks").size()).isEqualTo(2);
    }

    @Test
    void task_get_returns_task_snapshot(@TempDir Path workspace) throws IOException {
        TaskRegistry tasks = new TaskRegistry();
        Task created = tasks.create("hello", null);
        ToolDispatcher dispatcher =
                dispatcher_for(workspace, tasks, new TeamRegistry(), new CronRegistry(), new LspRegistry());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("task_id", created.task_id());

        ToolResult result = dispatcher.execute("TaskGet", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("task_id").asText()).isEqualTo(created.task_id());
        assertThat(payload.get("prompt").asText()).isEqualTo("hello");
    }

    @Test
    void task_get_for_unknown_id_returns_error(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("task_id", "task_unknown");

        ToolResult result = dispatcher.execute("TaskGet", input);

        assertThat(result.is_error()).isTrue();
        assertThat(result.output()).contains("task not found");
    }

    @Test
    void task_stop_marks_task_stopped(@TempDir Path workspace) throws IOException {
        TaskRegistry tasks = new TaskRegistry();
        Task created = tasks.create("a thing", null);
        ToolDispatcher dispatcher =
                dispatcher_for(workspace, tasks, new TeamRegistry(), new CronRegistry(), new LspRegistry());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("task_id", created.task_id());

        ToolResult result = dispatcher.execute("TaskStop", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("status").asText()).isEqualTo("stopped");
        assertThat(tasks.get(created.task_id()).orElseThrow().status().wire()).isEqualTo("stopped");
    }

    @Test
    void task_update_records_user_message(@TempDir Path workspace) throws IOException {
        TaskRegistry tasks = new TaskRegistry();
        Task created = tasks.create("p", null);
        ToolDispatcher dispatcher =
                dispatcher_for(workspace, tasks, new TeamRegistry(), new CronRegistry(), new LspRegistry());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("task_id", created.task_id());
        input.put("message", "additional context");

        ToolResult result = dispatcher.execute("TaskUpdate", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("message_count").asInt()).isEqualTo(1);
        assertThat(payload.get("last_message").asText()).isEqualTo("additional context");
    }

    @Test
    void task_output_returns_accumulated_output(@TempDir Path workspace) throws IOException {
        TaskRegistry tasks = new TaskRegistry();
        Task created = tasks.create("p", null);
        tasks.append_output(created.task_id(), "stdout-chunk");
        ToolDispatcher dispatcher =
                dispatcher_for(workspace, tasks, new TeamRegistry(), new CronRegistry(), new LspRegistry());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("task_id", created.task_id());

        ToolResult result = dispatcher.execute("TaskOutput", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("output").asText()).isEqualTo("stdout-chunk");
        assertThat(payload.get("has_output").asBoolean()).isTrue();
    }

    @Test
    void run_task_packet_validates_required_fields(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("objective", "ship feature");
        input.put("scope", "workspace");
        input.put("repo", "demo");
        input.put("branch_policy", "feature/foo");
        input.put("commit_policy", "atomic");
        input.put("reporting_contract", "summary");
        input.put("escalation_policy", "owner");
        ArrayNode tests = input.putArray("acceptance_tests");
        tests.add("./gradlew test");

        ToolResult result = dispatcher.execute("RunTaskPacket", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("task_id").asText()).startsWith("task_");
        assertThat(payload.get("task_packet").get("objective").asText()).isEqualTo("ship feature");
    }

    @Test
    void team_create_dispatches_to_runtime_team_registry(@TempDir Path workspace) throws IOException {
        TaskRegistry tasks = new TaskRegistry();
        Task one = tasks.create("first", null);
        TeamRegistry teams = new TeamRegistry();
        ToolDispatcher dispatcher = dispatcher_for(workspace, tasks, teams, new CronRegistry(), new LspRegistry());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("name", "alpha");
        ArrayNode taskList = input.putArray("tasks");
        ObjectNode entry = taskList.addObject();
        entry.put("task_id", one.task_id());

        ToolResult result = dispatcher.execute("TeamCreate", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("team_id").asText()).startsWith("team_");
        assertThat(payload.get("name").asText()).isEqualTo("alpha");
        assertThat(payload.get("task_count").asInt()).isEqualTo(1);
        assertThat(teams.len()).isEqualTo(1);
    }

    @Test
    void team_delete_marks_team_deleted(@TempDir Path workspace) throws IOException {
        TeamRegistry teams = new TeamRegistry();
        org.jclaude.runtime.team.Team created = teams.create("alpha", List.of("task_1"));
        ToolDispatcher dispatcher =
                dispatcher_for(workspace, new TaskRegistry(), teams, new CronRegistry(), new LspRegistry());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("team_id", created.team_id());

        ToolResult result = dispatcher.execute("TeamDelete", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("status").asText()).isEqualTo("deleted");
    }

    @Test
    void cron_create_dispatches_to_cron_registry(@TempDir Path workspace) throws IOException {
        CronRegistry crons = new CronRegistry();
        ToolDispatcher dispatcher =
                dispatcher_for(workspace, new TaskRegistry(), new TeamRegistry(), crons, new LspRegistry());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("schedule", "*/5 * * * *");
        input.put("prompt", "ping");

        ToolResult result = dispatcher.execute("CronCreate", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("cron_id").asText()).startsWith("cron_");
        assertThat(payload.get("enabled").asBoolean()).isTrue();
        assertThat(crons.len()).isEqualTo(1);
    }

    @Test
    void cron_list_returns_registry_snapshot(@TempDir Path workspace) throws IOException {
        CronRegistry crons = new CronRegistry();
        CronEntry first = crons.create("*/5 * * * *", "ping", "p");
        crons.create("*/10 * * * *", "pong", null);
        ToolDispatcher dispatcher =
                dispatcher_for(workspace, new TaskRegistry(), new TeamRegistry(), crons, new LspRegistry());

        ToolResult result = dispatcher.execute("CronList", MAPPER.createObjectNode());

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("count").asInt()).isEqualTo(2);
        assertThat(payload.get("crons").size()).isEqualTo(2);
        assertThat(first.cron_id()).isNotEmpty();
    }

    @Test
    void cron_delete_removes_entry(@TempDir Path workspace) throws IOException {
        CronRegistry crons = new CronRegistry();
        CronEntry entry = crons.create("*/5 * * * *", "p", null);
        ToolDispatcher dispatcher =
                dispatcher_for(workspace, new TaskRegistry(), new TeamRegistry(), crons, new LspRegistry());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("cron_id", entry.cron_id());

        ToolResult result = dispatcher.execute("CronDelete", input);

        assertThat(result.is_error()).isFalse();
        assertThat(crons.is_empty()).isTrue();
    }

    @Test
    void lsp_dispatch_returns_structured_stub(@TempDir Path workspace) throws IOException {
        LspRegistry lsp = new LspRegistry();
        lsp.register("rust", LspServerStatus.CONNECTED, workspace.toString(), List.of("hover"));
        ToolDispatcher dispatcher =
                dispatcher_for(workspace, new TaskRegistry(), new TeamRegistry(), new CronRegistry(), lsp);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("action", "hover");
        input.put("path", "main.rs");
        input.put("line", 1);
        input.put("character", 4);

        ToolResult result = dispatcher.execute("LSP", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("action").asText()).isEqualTo("hover");
        assertThat(payload.get("language").asText()).isEqualTo("rust");
        assertThat(payload.get("status").asText()).isEqualTo("dispatched");
    }

    @Test
    void lsp_dispatch_for_diagnostics_returns_empty_list(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("action", "diagnostics");

        ToolResult result = dispatcher.execute("LSP", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("count").asInt()).isEqualTo(0);
    }

    @Test
    void notebook_edit_replaces_cell_source(@TempDir Path workspace) throws IOException {
        Path notebook = workspace.resolve("note.ipynb");
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode metadata = root.putObject("metadata");
        metadata.putObject("kernelspec").put("language", "python");
        ArrayNode cells = root.putArray("cells");
        ObjectNode cell = cells.addObject();
        cell.put("cell_type", "code");
        cell.put("id", "c1");
        cell.putObject("metadata");
        ArrayNode source = cell.putArray("source");
        source.add("print('old')");
        cell.putArray("outputs");
        cell.putNull("execution_count");
        Files.writeString(notebook, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("notebook_path", "note.ipynb");
        input.put("cell_id", "c1");
        input.put("new_source", "print('new')");
        input.put("edit_mode", "replace");

        ToolResult result = dispatcher.execute("NotebookEdit", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("cell_id").asText()).isEqualTo("c1");
        assertThat(payload.get("edit_mode").asText()).isEqualTo("replace");
        assertThat(Files.readString(notebook)).contains("print('new')");
    }

    @Test
    void notebook_edit_rejects_non_ipynb_path(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("notebook_path", "note.txt");

        ToolResult result = dispatcher.execute("NotebookEdit", input);

        assertThat(result.is_error()).isTrue();
        assertThat(result.output()).contains("Jupyter");
    }

    @Test
    void config_dispatch_returns_get_response_for_known_setting(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("setting", "theme");

        ToolResult result = dispatcher.execute("Config", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("setting").asText()).isEqualTo("theme");
        assertThat(payload.get("operation").asText()).isEqualTo("get");
        assertThat(payload.get("success").asBoolean()).isTrue();
    }

    @Test
    void web_fetch_returns_structured_payload(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("url", "http://127.0.0.1:1/none");
        input.put("prompt", "summarize");

        ToolResult result = dispatcher.execute("WebFetch", input);

        // WebFetch is allowed to fail (no live network) but must return structured output.
        if (result.is_error()) {
            JsonNode payload = MAPPER.readTree(result.output());
            assertThat(payload.get("url").asText()).contains("127.0.0.1");
            assertThat(payload.get("error").asText()).isNotBlank();
        } else {
            JsonNode payload = MAPPER.readTree(result.output());
            assertThat(payload.get("code").asInt()).isGreaterThanOrEqualTo(100);
        }
    }

    @Test
    void web_search_returns_phase_3_no_backend_payload(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("query", "java records");

        ToolResult result = dispatcher.execute("WebSearch", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("status").asText()).isEqualTo("no_backend");
        assertThat(payload.get("results").isArray()).isTrue();
    }

    @Test
    void skill_dispatch_returns_error_for_missing_skill(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "nonexistent-skill");

        ToolResult result = dispatcher.execute("Skill", input);

        assertThat(result.is_error()).isTrue();
        assertThat(result.output()).contains("Skill resolution failed");
    }

    @Test
    void agent_dispatch_returns_phase_3_stub_with_manifest(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "explore the repo");
        input.put("prompt", "list packages");

        ToolResult result = dispatcher.execute("Agent", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("agent_id").asText()).isEqualTo("agent_phase3_stub");
        assertThat(payload.get("error").asText()).isEqualTo("not yet implemented");
    }

    @Test
    void repl_dispatch_executes_python_or_bash_command(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("language", "bash");
        input.put("code", "echo hi");

        ToolResult result = dispatcher.execute("REPL", input);

        // bash should be reliably present; assert exit_code structure either way.
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("language").asText()).isEqualTo("bash");
        assertThat(payload.has("stdout")).isTrue();
        assertThat(payload.has("exit_code")).isTrue();
    }

    @Test
    void repl_dispatch_rejects_unsupported_language(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("language", "cobol");
        input.put("code", "DISPLAY 'hi'");

        ToolResult result = dispatcher.execute("REPL", input);

        assertThat(result.is_error()).isTrue();
        assertThat(result.output()).contains("unsupported REPL language");
    }

    @Test
    void powershell_returns_unsupported_on_non_windows(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("command", "Get-ChildItem");

        ToolResult result = dispatcher.execute("PowerShell", input);

        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            // On Windows, just verify the dispatch produced JSON output.
            assertThat(result.output()).isNotBlank();
        } else {
            assertThat(result.is_error()).isTrue();
            JsonNode payload = MAPPER.readTree(result.output());
            assertThat(payload.get("error").asText()).isEqualTo("not yet implemented");
        }
    }

    @Test
    void ask_user_question_returns_unsupported_phase_3_stub(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("question", "Are we there yet?");

        ToolResult result = dispatcher.execute("AskUserQuestion", input);

        assertThat(result.is_error()).isTrue();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("error").asText()).isEqualTo("not yet implemented");
        assertThat(payload.get("tool").asText()).isEqualTo("AskUserQuestion");
    }

    @Test
    void mcp_tools_return_phase_3_stub(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);

        ToolResult list_result = dispatcher.execute("ListMcpResources", MAPPER.createObjectNode());
        assertThat(list_result.is_error()).isFalse();
        JsonNode list_payload = MAPPER.readTree(list_result.output());
        assertThat(list_payload.get("error").asText()).isEqualTo("not yet implemented");

        ObjectNode read_input = MAPPER.createObjectNode();
        read_input.put("uri", "memory://demo");
        ToolResult read_result = dispatcher.execute("ReadMcpResource", read_input);
        assertThat(read_result.is_error()).isFalse();
        JsonNode read_payload = MAPPER.readTree(read_result.output());
        assertThat(read_payload.get("error").asText()).isEqualTo("not yet implemented");

        ObjectNode auth_input = MAPPER.createObjectNode();
        auth_input.put("server", "demo");
        ToolResult auth_result = dispatcher.execute("McpAuth", auth_input);
        assertThat(auth_result.is_error()).isTrue();
        JsonNode auth_payload = MAPPER.readTree(auth_result.output());
        assertThat(auth_payload.get("error").asText()).isEqualTo("not yet implemented");

        ObjectNode mcp_input = MAPPER.createObjectNode();
        mcp_input.put("server", "demo");
        mcp_input.put("tool", "demo_tool");
        ToolResult mcp_result = dispatcher.execute("MCP", mcp_input);
        assertThat(mcp_result.is_error()).isTrue();
        JsonNode mcp_payload = MAPPER.readTree(mcp_result.output());
        assertThat(mcp_payload.get("error").asText()).isEqualTo("not yet implemented");
    }

    @Test
    void worker_tools_return_phase_3_stub(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", "worker_demo");

        ToolResult result = dispatcher.execute("WorkerGet", input);

        assertThat(result.is_error()).isTrue();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("error").asText()).isEqualTo("not yet implemented");
        assertThat(payload.get("tool").asText()).isEqualTo("WorkerGet");
    }

    @Test
    void remote_trigger_returns_structured_response_or_error(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("url", "http://127.0.0.1:1/never");
        input.put("method", "GET");

        ToolResult result = dispatcher.execute("RemoteTrigger", input);

        // Remote trigger never throws — it always returns structured JSON.
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("url").asText()).contains("127.0.0.1");
        assertThat(payload.get("method").asText()).isEqualTo("GET");
    }

    @Test
    void testing_permission_returns_stub_payload(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("action", "verify");

        ToolResult result = dispatcher.execute("TestingPermission", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("permitted").asBoolean()).isTrue();
        assertThat(payload.get("action").asText()).isEqualTo("verify");
    }

    @Test
    void brief_alias_dispatches_like_send_user_message(@TempDir Path workspace) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ToolDispatcher dispatcher = new ToolDispatcher(
                workspace,
                new GlobalToolRegistry(MvpToolSpecs.all_tool_specs()),
                new TodoStore(),
                new PlanModeState(),
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        ObjectNode input = MAPPER.createObjectNode();
        input.put("message", "via brief");

        ToolResult result = dispatcher.execute("Brief", input);

        assertThat(result.is_error()).isFalse();
        assertThat(sink.toString(StandardCharsets.UTF_8)).contains("via brief");
    }

    @Test
    void enter_worktree_returns_unsupported_phase_3_stub(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);

        ToolResult result = dispatcher.execute("EnterWorktree", MAPPER.createObjectNode());

        assertThat(result.is_error()).isTrue();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("error").asText()).isEqualTo("not yet implemented");
    }

    @Test
    void mcp_claude_ai_authenticators_return_unsupported_phase_3_stub(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);

        ToolResult result = dispatcher.execute("mcp__claude_ai_Gmail__authenticate", MAPPER.createObjectNode());

        assertThat(result.is_error()).isTrue();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("error").asText()).isEqualTo("not yet implemented");
    }
}
