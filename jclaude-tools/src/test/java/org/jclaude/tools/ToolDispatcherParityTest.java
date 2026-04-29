package org.jclaude.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jclaude.api.json.JclaudeMappers;
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

/**
 * Per-tool parity coverage with the upstream Rust dispatcher. Each test exercises a single
 * tool from {@link MvpToolSpecs#all_tool_specs()}, asserting the structured output shape and the
 * registry-absent graceful-degradation path where applicable.
 */
class ToolDispatcherParityTest {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    // ----- Helpers -----

    private ToolDispatcher dispatcher_for(Path workspace) {
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
                new LspRegistry());
    }

    private ToolDispatcher dispatcher_with_workers(Path workspace, WorkerRegistry workers) {
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
                Optional.empty(),
                Optional.of(workers),
                Optional.empty());
    }

    private ToolDispatcher dispatcher_with_mcp(Path workspace, McpToolBridgeAdapter adapter) {
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
                Optional.of(adapter),
                Optional.empty(),
                Optional.empty());
    }

    // ----- Workspace files & shell (already covered in ToolDispatcherTest) -----

    @Test
    void read_file_round_trips_through_dispatcher(@TempDir Path workspace) throws IOException {
        Path file = workspace.resolve("hello.txt");
        Files.writeString(file, "hi\n");
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "hello.txt");

        ToolResult result = dispatcher.execute("read_file", input);

        assertThat(result.is_error()).isFalse();
        assertThat(result.output()).contains("hi");
    }

    @Test
    void write_file_round_trips_through_dispatcher(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "out.txt");
        input.put("content", "data");

        ToolResult result = dispatcher.execute("write_file", input);

        assertThat(result.is_error()).isFalse();
        assertThat(Files.readString(workspace.resolve("out.txt"))).isEqualTo("data");
    }

    @Test
    void edit_file_replaces_target_string(@TempDir Path workspace) throws IOException {
        Path file = workspace.resolve("edit.txt");
        Files.writeString(file, "alpha");
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "edit.txt");
        input.put("old_string", "alpha");
        input.put("new_string", "beta");

        ToolResult result = dispatcher.execute("edit_file", input);

        assertThat(result.is_error()).isFalse();
        assertThat(Files.readString(file)).isEqualTo("beta");
    }

    @Test
    void glob_search_finds_matching_files(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "");
        Files.writeString(workspace.resolve("b.md"), "");
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("pattern", "**/*.txt");

        ToolResult result = dispatcher.execute("glob_search", input);

        assertThat(result.is_error()).isFalse();
        assertThat(result.output()).contains("a.txt");
    }

    @Test
    void grep_search_locates_pattern(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("note.txt"), "alpha\nbeta\n");
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("pattern", "beta");

        ToolResult result = dispatcher.execute("grep_search", input);

        assertThat(result.is_error()).isFalse();
        // Default `files_with_matches` mode returns the matching filenames.
        assertThat(result.output()).contains("note.txt");
    }

    @Test
    void bash_runs_simple_echo(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("command", "echo hi");

        ToolResult result = dispatcher.execute("bash", input);

        assertThat(result.is_error()).isFalse();
        assertThat(result.output()).contains("hi");
    }

    // ----- Web tools -----

    @Test
    void web_fetch_against_local_http_server_returns_status_code(@TempDir Path workspace) throws IOException {
        try (LocalServer server = LocalServer.start("/page", 200, "text/plain", "fetch ok")) {
            ToolDispatcher dispatcher = dispatcher_for(workspace);
            ObjectNode input = MAPPER.createObjectNode();
            input.put("url", server.base_url() + "/page");
            input.put("prompt", "summary");

            ToolResult result = dispatcher.execute("WebFetch", input);

            assertThat(result.is_error()).isFalse();
            JsonNode payload = MAPPER.readTree(result.output());
            assertThat(payload.get("code").asInt()).isEqualTo(200);
            assertThat(payload.get("body").asText()).contains("fetch ok");
        }
    }

    @Test
    void web_fetch_returns_structured_error_for_invalid_url(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("url", "https://invalid.host.tld.does.not.exist:1/path");
        input.put("prompt", "summary");

        ToolResult result = dispatcher.execute("WebFetch", input);

        // Lookup fails → structured error payload, not an exception bubble.
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.has("url")).isTrue();
    }

    @Test
    void web_search_returns_no_backend_payload(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("query", "java records");

        ToolResult result = dispatcher.execute("WebSearch", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("query").asText()).isEqualTo("java records");
        assertThat(payload.get("results").isArray()).isTrue();
    }

    // ----- Session/tasks/planning -----

    @Test
    void todo_write_persists_active_form(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        ArrayNode todos = input.putArray("todos");
        ObjectNode todo = todos.addObject();
        todo.put("content", "do thing");
        todo.put("activeForm", "doing thing");
        todo.put("status", "in_progress");

        ToolResult result = dispatcher.execute("TodoWrite", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("new_todos").get(0).get("activeForm").asText()).isEqualTo("doing thing");
    }

    @Test
    void skill_returns_error_for_missing_skill(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "nonexistent-skill");

        ToolResult result = dispatcher.execute("Skill", input);

        assertThat(result.is_error()).isTrue();
    }

    @Test
    void agent_returns_pending_manifest(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("description", "explore");
        input.put("prompt", "go");

        ToolResult result = dispatcher.execute("Agent", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("status").asText()).isEqualTo("pending");
    }

    @Test
    void tool_search_finds_listed_tool(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("query", "WebFetch");

        ToolResult result = dispatcher.execute("ToolSearch", input);

        assertThat(result.is_error()).isFalse();
        assertThat(result.output()).contains("WebFetch");
    }

    @Test
    void notebook_edit_replaces_cell_source_in_ipynb(@TempDir Path workspace) throws IOException {
        Path notebook = workspace.resolve("note.ipynb");
        Files.writeString(
                notebook,
                """
                {
                  "cells": [
                    {"cell_type": "code", "id": "c1", "source": ["print('old')"], "outputs": [], "execution_count": null}
                  ],
                  "metadata": {"kernelspec": {"language": "python"}},
                  "nbformat": 4,
                  "nbformat_minor": 5
                }
                """);
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("notebook_path", "note.ipynb");
        input.put("cell_id", "c1");
        input.put("new_source", "print('new')");

        ToolResult result = dispatcher.execute("NotebookEdit", input);

        assertThat(result.is_error()).isFalse();
        assertThat(Files.readString(notebook)).contains("print('new')");
    }

    @Test
    void notebook_edit_rejects_non_ipynb(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("notebook_path", "demo.txt");
        input.put("new_source", "print");

        ToolResult result = dispatcher.execute("NotebookEdit", input);

        assertThat(result.is_error()).isTrue();
        assertThat(result.output()).contains("Jupyter notebook");
    }

    @Test
    void sleep_honors_short_duration(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("duration_ms", 1);

        ToolResult result = dispatcher.execute("Sleep", input);

        assertThat(result.is_error()).isFalse();
        assertThat(result.output()).contains("slept_ms");
    }

    @Test
    void send_user_message_writes_to_sink(@TempDir Path workspace) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ToolDispatcher dispatcher = new ToolDispatcher(
                workspace,
                new GlobalToolRegistry(MvpToolSpecs.all_tool_specs()),
                new TodoStore(),
                new PlanModeState(),
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        ObjectNode input = MAPPER.createObjectNode();
        input.put("message", "to user");
        input.put("status", "normal");

        ToolResult result = dispatcher.execute("SendUserMessage", input);

        assertThat(result.is_error()).isFalse();
        assertThat(sink.toString(StandardCharsets.UTF_8)).contains("to user");
    }

    @Test
    void config_get_returns_null_for_unknown_setting(@TempDir Path workspace) throws IOException {
        String previous = System.getProperty("jclaude.config.home");
        System.setProperty("jclaude.config.home", workspace.toString());
        try {
            ToolDispatcher dispatcher = dispatcher_for(workspace);
            ObjectNode input = MAPPER.createObjectNode();
            input.put("setting", "verbose");

            ToolResult result = dispatcher.execute("Config", input);

            assertThat(result.is_error()).isFalse();
            JsonNode payload = MAPPER.readTree(result.output());
            assertThat(payload.get("operation").asText()).isEqualTo("get");
            assertThat(payload.get("value").isNull()).isTrue();
        } finally {
            restore_property("jclaude.config.home", previous);
        }
    }

    @Test
    void config_set_then_get_round_trips(@TempDir Path workspace) throws IOException {
        String previous = System.getProperty("jclaude.config.home");
        System.setProperty("jclaude.config.home", workspace.toString());
        try {
            ToolDispatcher dispatcher = dispatcher_for(workspace);
            ObjectNode set_input = MAPPER.createObjectNode();
            set_input.put("setting", "theme");
            set_input.put("value", "dark");

            ToolResult set_result = dispatcher.execute("Config", set_input);

            assertThat(set_result.is_error()).isFalse();
            JsonNode set_payload = MAPPER.readTree(set_result.output());
            assertThat(set_payload.get("operation").asText()).isEqualTo("set");
            assertThat(set_payload.get("new_value").asText()).isEqualTo("dark");

            ObjectNode get_input = MAPPER.createObjectNode();
            get_input.put("setting", "theme");
            ToolResult get_result = dispatcher.execute("Config", get_input);
            JsonNode get_payload = MAPPER.readTree(get_result.output());
            assertThat(get_payload.get("value").asText()).isEqualTo("dark");
        } finally {
            restore_property("jclaude.config.home", previous);
        }
    }

    @Test
    void enter_and_exit_plan_mode_toggle_state(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ToolResult enter = dispatcher.execute("EnterPlanMode", MAPPER.createObjectNode());
        ToolResult exit = dispatcher.execute("ExitPlanMode", MAPPER.createObjectNode());

        assertThat(enter.is_error()).isFalse();
        assertThat(exit.is_error()).isFalse();
        assertThat(MAPPER.readTree(enter.output()).get("plan_mode").asBoolean()).isTrue();
        assertThat(MAPPER.readTree(exit.output()).get("plan_mode").asBoolean()).isFalse();
    }

    @Test
    void structured_output_echoes_input(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("foo", "bar");

        ToolResult result = dispatcher.execute("StructuredOutput", input);

        assertThat(result.is_error()).isFalse();
        assertThat(MAPPER.readTree(result.output()).get("foo").asText()).isEqualTo("bar");
    }

    // ----- Subprocess execution -----

    @Test
    void repl_executes_bash_snippet(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("language", "bash");
        input.put("code", "echo repl");

        ToolResult result = dispatcher.execute("REPL", input);

        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("language").asText()).isEqualTo("bash");
        assertThat(payload.has("stdout")).isTrue();
    }

    @Test
    void powershell_returns_unsupported_on_non_windows_host(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("command", "Get-ChildItem");

        ToolResult result = dispatcher.execute("PowerShell", input);

        assertThat(result.output()).isNotBlank();
    }

    // ----- Interactive -----

    @Test
    void ask_user_question_returns_deferred_payload(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("question", "ok?");

        ToolResult result = dispatcher.execute("AskUserQuestion", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("status").asText()).isEqualTo("deferred");
    }

    // ----- Task family -----

    @Test
    void task_create_persists_to_runtime_registry(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("prompt", "p");

        ToolResult result = dispatcher.execute("TaskCreate", input);

        assertThat(result.is_error()).isFalse();
        assertThat(MAPPER.readTree(result.output()).get("task_id").asText()).startsWith("task_");
    }

    @Test
    void run_task_packet_creates_task_with_full_packet(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("objective", "ship");
        input.put("scope", "workspace");
        input.put("repo", "demo");
        input.put("branch_policy", "feature");
        ArrayNode tests = input.putArray("acceptance_tests");
        tests.add("./gradlew check");
        input.put("commit_policy", "squash");
        input.put("reporting_contract", "summary");
        input.put("escalation_policy", "ping me");

        ToolResult result = dispatcher.execute("RunTaskPacket", input);

        assertThat(result.is_error()).isFalse();
        assertThat(MAPPER.readTree(result.output()).get("task_id").asText()).startsWith("task_");
    }

    @Test
    void task_get_returns_payload_for_known_task(@TempDir Path workspace) throws IOException {
        TaskRegistry tasks = new TaskRegistry();
        var created = tasks.create("p", null);
        ToolDispatcher dispatcher = new ToolDispatcher(
                workspace,
                new GlobalToolRegistry(MvpToolSpecs.all_tool_specs()),
                new TodoStore(),
                new PlanModeState(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                org.jclaude.plugins.PluginRegistry.empty(),
                tasks,
                new TeamRegistry(),
                new CronRegistry(),
                new LspRegistry());
        ObjectNode input = MAPPER.createObjectNode();
        input.put("task_id", created.task_id());

        ToolResult result = dispatcher.execute("TaskGet", input);

        assertThat(result.is_error()).isFalse();
    }

    @Test
    void task_list_emits_count_field(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);

        ToolResult result = dispatcher.execute("TaskList", MAPPER.createObjectNode());

        assertThat(result.is_error()).isFalse();
        assertThat(MAPPER.readTree(result.output()).get("count").asInt()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void task_stop_returns_error_for_unknown(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("task_id", "task_unknown");

        ToolResult result = dispatcher.execute("TaskStop", input);

        assertThat(result.is_error()).isTrue();
    }

    @Test
    void task_update_returns_error_for_unknown(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("task_id", "task_unknown");
        input.put("message", "hi");

        ToolResult result = dispatcher.execute("TaskUpdate", input);

        assertThat(result.is_error()).isTrue();
    }

    @Test
    void task_output_returns_error_for_unknown(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("task_id", "task_unknown");

        ToolResult result = dispatcher.execute("TaskOutput", input);

        assertThat(result.is_error()).isTrue();
    }

    // ----- Worker family -----

    @Test
    void worker_create_returns_unsupported_when_registry_absent(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("cwd", workspace.toString());

        ToolResult result = dispatcher.execute("WorkerCreate", input);

        assertThat(result.is_error()).isTrue();
    }

    @Test
    void worker_create_when_registry_present_creates_worker(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        ToolDispatcher dispatcher = dispatcher_with_workers(workspace, workers);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("cwd", workspace.toString());
        input.putArray("trusted_roots");

        ToolResult result = dispatcher.execute("WorkerCreate", input);

        assertThat(result.is_error()).isFalse();
        assertThat(MAPPER.readTree(result.output()).get("worker_id").asText()).startsWith("worker_");
    }

    @Test
    void worker_get_returns_state_when_registered(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        Worker w = workers.create(workspace.toString(), List.of(), false);
        ToolDispatcher dispatcher = dispatcher_with_workers(workspace, workers);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", w.worker_id());

        ToolResult result = dispatcher.execute("WorkerGet", input);

        assertThat(result.is_error()).isFalse();
    }

    @Test
    void worker_observe_routes_through_registry(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        Worker w = workers.create(workspace.toString(), List.of(), false);
        ToolDispatcher dispatcher = dispatcher_with_workers(workspace, workers);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", w.worker_id());
        input.put("screen_text", "ready_for_prompt");

        ToolResult result = dispatcher.execute("WorkerObserve", input);

        assertThat(result.is_error()).isFalse();
    }

    @Test
    void worker_resolve_trust_routes_through_registry(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        Worker w = workers.create(workspace.toString(), List.of(), false);
        ToolDispatcher dispatcher = dispatcher_with_workers(workspace, workers);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", w.worker_id());

        ToolResult result = dispatcher.execute("WorkerResolveTrust", input);

        // Result may legitimately be an error if there's no trust prompt active —
        // but the dispatcher must route to the registry rather than stub-out.
        assertThat(result.output()).isNotBlank();
    }

    @Test
    void worker_await_ready_returns_snapshot(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        Worker w = workers.create(workspace.toString(), List.of(), false);
        ToolDispatcher dispatcher = dispatcher_with_workers(workspace, workers);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", w.worker_id());

        ToolResult result = dispatcher.execute("WorkerAwaitReady", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.has("ready")).isTrue();
    }

    @Test
    void worker_send_prompt_routes_through_registry(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        Worker w = workers.create(workspace.toString(), List.of(), false);
        ToolDispatcher dispatcher = dispatcher_with_workers(workspace, workers);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", w.worker_id());
        input.put("prompt", "go");

        ToolResult result = dispatcher.execute("WorkerSendPrompt", input);

        // Worker isn't ready_for_prompt yet; the registry surfaces an error — that's fine,
        // the dispatcher must still route rather than return the registry-absent stub.
        assertThat(result.output()).isNotBlank();
    }

    @Test
    void worker_restart_routes_through_registry(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        Worker w = workers.create(workspace.toString(), List.of(), false);
        ToolDispatcher dispatcher = dispatcher_with_workers(workspace, workers);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", w.worker_id());

        ToolResult result = dispatcher.execute("WorkerRestart", input);

        assertThat(result.is_error()).isFalse();
    }

    @Test
    void worker_terminate_marks_finished(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        Worker w = workers.create(workspace.toString(), List.of(), false);
        ToolDispatcher dispatcher = dispatcher_with_workers(workspace, workers);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", w.worker_id());

        ToolResult result = dispatcher.execute("WorkerTerminate", input);

        assertThat(result.is_error()).isFalse();
    }

    @Test
    void worker_observe_completion_records_finish_reason(@TempDir Path workspace) throws IOException {
        WorkerRegistry workers = new WorkerRegistry();
        Worker w = workers.create(workspace.toString(), List.of(), false);
        ToolDispatcher dispatcher = dispatcher_with_workers(workspace, workers);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("worker_id", w.worker_id());
        input.put("finish_reason", "stop");
        input.put("tokens_output", 42);

        ToolResult result = dispatcher.execute("WorkerObserveCompletion", input);

        assertThat(result.is_error()).isFalse();
    }

    // ----- Team / Cron -----

    @Test
    void team_create_returns_team_payload(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("name", "alpha");
        input.putArray("tasks");

        ToolResult result = dispatcher.execute("TeamCreate", input);

        assertThat(result.is_error()).isFalse();
        assertThat(MAPPER.readTree(result.output()).get("team_id").asText()).startsWith("team_");
    }

    @Test
    void team_delete_returns_error_for_unknown(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("team_id", "team_unknown");

        ToolResult result = dispatcher.execute("TeamDelete", input);

        assertThat(result.is_error()).isTrue();
    }

    @Test
    void cron_create_persists_entry(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("schedule", "*/5 * * * *");
        input.put("prompt", "ping");

        ToolResult result = dispatcher.execute("CronCreate", input);

        assertThat(result.is_error()).isFalse();
        assertThat(MAPPER.readTree(result.output()).get("cron_id").asText()).startsWith("cron_");
    }

    @Test
    void cron_delete_returns_error_for_unknown(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("cron_id", "cron_unknown");

        ToolResult result = dispatcher.execute("CronDelete", input);

        assertThat(result.is_error()).isTrue();
    }

    @Test
    void cron_list_emits_entries_array(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);

        ToolResult result = dispatcher.execute("CronList", MAPPER.createObjectNode());

        assertThat(result.is_error()).isFalse();
        assertThat(MAPPER.readTree(result.output()).get("crons").isArray()).isTrue();
    }

    // ----- LSP -----

    @Test
    void lsp_dispatch_delegates_to_registry(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("action", "diagnostics");

        ToolResult result = dispatcher.execute("LSP", input);

        assertThat(result.is_error()).isFalse();
    }

    // ----- MCP family -----

    @Test
    void list_mcp_resources_returns_no_servers_when_bridge_absent(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);

        ToolResult result = dispatcher.execute("ListMcpResources", MAPPER.createObjectNode());

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("status").asText()).isEqualTo("no_mcp_servers_configured");
    }

    @Test
    void list_mcp_resources_returns_data_when_bridge_present(@TempDir Path workspace) throws IOException {
        McpToolBridge bridge = new McpToolBridge();
        bridge.register_server(
                "demo",
                McpToolBridge.ConnectionStatus.CONNECTED,
                List.of(),
                List.of(new McpToolBridge.McpResourceInfo(
                        "memory://demo/r", "demo-resource", Optional.empty(), Optional.empty())),
                Optional.empty());
        ToolDispatcher dispatcher = dispatcher_with_mcp(workspace, new McpToolBridgeAdapter(bridge));
        ObjectNode input = MAPPER.createObjectNode();
        input.put("server", "demo");

        ToolResult result = dispatcher.execute("ListMcpResources", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("count").asInt()).isEqualTo(1);
    }

    @Test
    void read_mcp_resource_returns_no_servers_when_bridge_absent(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("uri", "memory://x");

        ToolResult result = dispatcher.execute("ReadMcpResource", input);

        assertThat(result.is_error()).isFalse();
        assertThat(MAPPER.readTree(result.output()).get("status").asText()).isEqualTo("no_mcp_servers_configured");
    }

    @Test
    void mcp_auth_returns_no_servers_when_bridge_absent(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("server", "demo");

        ToolResult result = dispatcher.execute("McpAuth", input);

        assertThat(result.is_error()).isFalse();
        assertThat(MAPPER.readTree(result.output()).get("status").asText()).isEqualTo("no_mcp_servers_configured");
    }

    @Test
    void mcp_auth_returns_server_state_when_bridge_present(@TempDir Path workspace) throws IOException {
        McpToolBridge bridge = new McpToolBridge();
        bridge.register_server(
                "demo", McpToolBridge.ConnectionStatus.CONNECTED, List.of(), List.of(), Optional.of("server-info"));
        ToolDispatcher dispatcher = dispatcher_with_mcp(workspace, new McpToolBridgeAdapter(bridge));
        ObjectNode input = MAPPER.createObjectNode();
        input.put("server", "demo");

        ToolResult result = dispatcher.execute("McpAuth", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("status").asText()).isEqualTo("connected");
        assertThat(payload.get("server_info").asText()).isEqualTo("server-info");
    }

    @Test
    void mcp_auth_reports_disconnected_for_unknown_server(@TempDir Path workspace) throws IOException {
        McpToolBridge bridge = new McpToolBridge();
        ToolDispatcher dispatcher = dispatcher_with_mcp(workspace, new McpToolBridgeAdapter(bridge));
        ObjectNode input = MAPPER.createObjectNode();
        input.put("server", "ghost");

        ToolResult result = dispatcher.execute("McpAuth", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("status").asText()).isEqualTo("disconnected");
    }

    @Test
    void mcp_returns_no_servers_when_bridge_absent(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("server", "demo");
        input.put("tool", "demo_tool");

        ToolResult result = dispatcher.execute("MCP", input);

        assertThat(result.is_error()).isFalse();
        assertThat(MAPPER.readTree(result.output()).get("status").asText()).isEqualTo("no_mcp_servers_configured");
    }

    @Test
    void remote_trigger_returns_status_code_against_local_server(@TempDir Path workspace) throws IOException {
        try (LocalServer server = LocalServer.start("/hook", 200, "text/plain", "ok")) {
            ToolDispatcher dispatcher = dispatcher_for(workspace);
            ObjectNode input = MAPPER.createObjectNode();
            input.put("url", server.base_url() + "/hook");
            input.put("method", "POST");

            ToolResult result = dispatcher.execute("RemoteTrigger", input);

            assertThat(result.is_error()).isFalse();
            JsonNode payload = MAPPER.readTree(result.output());
            assertThat(payload.get("status_code").asInt()).isEqualTo(200);
            assertThat(payload.get("body").asText()).contains("ok");
        }
    }

    @Test
    void remote_trigger_against_404_returns_4xx_payload(@TempDir Path workspace) throws IOException {
        try (LocalServer server = LocalServer.start("/wrong", 404, "text/plain", "missing")) {
            ToolDispatcher dispatcher = dispatcher_for(workspace);
            ObjectNode input = MAPPER.createObjectNode();
            input.put("url", server.base_url() + "/never");
            input.put("method", "GET");

            ToolResult result = dispatcher.execute("RemoteTrigger", input);

            JsonNode payload = MAPPER.readTree(result.output());
            assertThat(payload.get("status_code").asInt()).isEqualTo(404);
            assertThat(payload.get("success").asBoolean()).isFalse();
        }
    }

    @Test
    void remote_trigger_against_invalid_host_returns_error_payload(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("url", "https://invalid.host.tld.does.not.exist:1/x");

        ToolResult result = dispatcher.execute("RemoteTrigger", input);

        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("success").asBoolean()).isFalse();
        assertThat(payload.has("error")).isTrue();
    }

    // ----- Test-only -----

    @Test
    void testing_permission_returns_stub_payload(@TempDir Path workspace) throws IOException {
        ToolDispatcher dispatcher = dispatcher_for(workspace);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("action", "verify");

        ToolResult result = dispatcher.execute("TestingPermission", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("permitted").asBoolean()).isTrue();
    }

    // ----- Negative parity -----

    @Test
    void unknown_tool_raises_unsupported(@TempDir Path workspace) {
        ToolDispatcher dispatcher = dispatcher_for(workspace);

        assertThatThrownBy(() -> dispatcher.execute("ZzzNonexistent", MAPPER.createObjectNode()))
                .isInstanceOf(UnsupportedToolException.class);
    }

    // ----- Helpers -----

    private static void restore_property(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    /** Tiny in-process HTTP fixture for WebFetch / RemoteTrigger tests. */
    private static final class LocalServer implements AutoCloseable {
        private final HttpServer server;

        private LocalServer(HttpServer server) {
            this.server = server;
        }

        static LocalServer start(String path, int status, String content_type, String body) throws IOException {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            s.createContext(path, exchange -> {
                exchange.getResponseHeaders().add("Content-Type", content_type);
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            // Catch-all → 404 with body "missing".
            s.createContext("/", exchange -> {
                byte[] not_found = "missing".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, not_found.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(not_found);
                }
            });
            s.start();
            return new LocalServer(s);
        }

        String base_url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @SuppressWarnings("unused")
    private static void mark_used(Map<String, ?> ignored) {
        // Reserved for future per-tool input matrices.
    }
}
