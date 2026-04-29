package org.jclaude.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.jclaude.api.json.JclaudeMappers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolDispatcherTest {

    private static final ObjectMapper MAPPER = JclaudeMappers.standard();

    private ToolDispatcher dispatcher_for(Path workspace) {
        return new ToolDispatcher(
                workspace,
                new GlobalToolRegistry(MvpToolSpecs.mvp_tool_specs()),
                new TodoStore(),
                new PlanModeState(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    @Test
    void read_file_dispatches_to_runtime_files_read_file(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("hi.txt"), "alpha\nbeta");

        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "hi.txt");

        ToolResult result = dispatcher_for(workspace).execute("read_file", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("kind").asText()).isEqualTo("text");
        assertThat(payload.get("file").get("content").asText()).isEqualTo("alpha\nbeta");
    }

    @Test
    void read_file_auto_resolves_bare_filename_to_unique_workspace_match(@TempDir Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("a/b/c"));
        Files.writeString(workspace.resolve("a/b/c/Foo.java"), "class Foo {}");

        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "Foo.java");

        ToolResult result = dispatcher_for(workspace).execute("read_file", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("file").get("content").asText()).isEqualTo("class Foo {}");
        assertThat(payload.get("file").get("file_path").asText()).endsWith("a/b/c/Foo.java");
    }

    @Test
    void read_file_returns_ambiguous_error_with_candidates_when_multiple_matches(@TempDir Path workspace)
            throws IOException {
        Files.createDirectories(workspace.resolve("x"));
        Files.createDirectories(workspace.resolve("y"));
        Files.writeString(workspace.resolve("x/Bar.java"), "class Bar {}");
        Files.writeString(workspace.resolve("y/Bar.java"), "class Bar2 {}");

        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "Bar.java");

        ToolResult result = dispatcher_for(workspace).execute("read_file", input);

        assertThat(result.is_error()).isTrue();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("error").asText()).contains("ambiguous filename: Bar.java");
        ArrayNode candidates = (ArrayNode) payload.get("candidates");
        assertThat(candidates).hasSize(2);
    }

    @Test
    void read_file_does_not_auto_resolve_when_path_has_directory_component(@TempDir Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("a/b"));
        Files.writeString(workspace.resolve("a/b/Foo.java"), "class Foo {}");

        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "missing/Foo.java"); // any '/' disables the auto-resolve

        ToolResult result = dispatcher_for(workspace).execute("read_file", input);

        assertThat(result.is_error()).isTrue();
        assertThat(result.output().toLowerCase()).contains("no such file or directory");
    }

    @Test
    void read_file_returns_original_not_found_when_no_workspace_match(@TempDir Path workspace) throws IOException {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "Nothing.java");

        ToolResult result = dispatcher_for(workspace).execute("read_file", input);

        assertThat(result.is_error()).isTrue();
        assertThat(result.output().toLowerCase()).contains("no such file or directory");
    }

    @Test
    void write_file_dispatches_to_runtime_files_write_file(@TempDir Path workspace) throws IOException {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "out.txt");
        input.put("content", "hello world");

        ToolResult result = dispatcher_for(workspace).execute("write_file", input);

        assertThat(result.is_error()).isFalse();
        assertThat(Files.readString(workspace.resolve("out.txt"))).isEqualTo("hello world");
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("content").asText()).isEqualTo("hello world");
    }

    @Test
    void edit_file_dispatches_to_runtime_files_edit_file(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("doc.md"), "alpha beta");
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "doc.md");
        input.put("old_string", "beta");
        input.put("new_string", "gamma");

        ToolResult result = dispatcher_for(workspace).execute("edit_file", input);

        assertThat(result.is_error()).isFalse();
        assertThat(Files.readString(workspace.resolve("doc.md"))).isEqualTo("alpha gamma");
    }

    @Test
    void glob_search_dispatches_to_runtime_files_glob_search(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "x");
        Files.writeString(workspace.resolve("b.md"), "y");
        ObjectNode input = MAPPER.createObjectNode();
        input.put("pattern", "*.txt");

        ToolResult result = dispatcher_for(workspace).execute("glob_search", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("num_files").asInt()).isEqualTo(1);
        assertThat(payload.get("filenames").get(0).asText()).endsWith("a.txt");
    }

    @Test
    void grep_search_dispatches_to_runtime_files_grep_search(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "needle here\nnope");
        Files.writeString(workspace.resolve("b.txt"), "nothing");
        ObjectNode input = MAPPER.createObjectNode();
        input.put("pattern", "needle");
        input.put("path", workspace.toString());

        ToolResult result = dispatcher_for(workspace).execute("grep_search", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("num_files").asInt()).isEqualTo(1);
    }

    @Test
    void bash_dispatches_to_runtime_bash_bash(@TempDir Path workspace) throws IOException {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("command", "echo hello");

        ToolResult result = dispatcher_for(workspace).execute("bash", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("stdout").asText()).contains("hello");
        assertThat(payload.get("exit_code").asInt()).isEqualTo(0);
    }

    @Test
    void bash_returns_is_error_when_exit_code_nonzero(@TempDir Path workspace) throws IOException {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("command", "exit 7");

        ToolResult result = dispatcher_for(workspace).execute("bash", input);

        assertThat(result.is_error()).isTrue();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("exit_code").asInt()).isEqualTo(7);
    }

    @Test
    void todo_write_dispatches_to_in_memory_store(@TempDir Path workspace) throws IOException {
        TodoStore store = new TodoStore();
        ToolDispatcher dispatcher = new ToolDispatcher(
                workspace,
                new GlobalToolRegistry(MvpToolSpecs.mvp_tool_specs()),
                store,
                new PlanModeState(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        ObjectNode input = MAPPER.createObjectNode();
        ArrayNode todos = input.putArray("todos");
        ObjectNode todo = todos.addObject();
        todo.put("id", "1");
        todo.put("content", "do thing");
        todo.put("status", "pending");

        ToolResult result = dispatcher.execute("TodoWrite", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("new_todos")).isNotNull();
        assertThat(payload.get("new_todos").size()).isEqualTo(1);
        assertThat(store.snapshot()).hasSize(1);
        assertThat(store.snapshot().get(0)).containsEntry("content", "do thing");
    }

    @Test
    void sleep_dispatches_with_slept_ms_response(@TempDir Path workspace) throws IOException {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("duration_ms", 5);

        ToolResult result = dispatcher_for(workspace).execute("Sleep", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("slept_ms").asLong()).isEqualTo(5L);
    }

    @Test
    void tool_search_dispatches_against_registry(@TempDir Path workspace) throws IOException {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("query", "read_file");

        ToolResult result = dispatcher_for(workspace).execute("ToolSearch", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        JsonNode matches = payload.get("matches");
        assertThat(matches.size()).isGreaterThanOrEqualTo(1);
        assertThat(matches.get(0).get("name").asText()).isEqualTo("read_file");
    }

    @Test
    void structured_output_dispatches_passthrough(@TempDir Path workspace) throws IOException {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("foo", "bar");
        input.put("count", 7);

        ToolResult result = dispatcher_for(workspace).execute("StructuredOutput", input);

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("foo").asText()).isEqualTo("bar");
        assertThat(payload.get("count").asInt()).isEqualTo(7);
    }

    @Test
    void enter_plan_mode_dispatches_and_sets_flag(@TempDir Path workspace) throws IOException {
        PlanModeState state = new PlanModeState();
        ToolDispatcher dispatcher = new ToolDispatcher(
                workspace,
                new GlobalToolRegistry(MvpToolSpecs.mvp_tool_specs()),
                new TodoStore(),
                state,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        ToolResult result = dispatcher.execute("EnterPlanMode", MAPPER.createObjectNode());

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("plan_mode").asBoolean()).isTrue();
        assertThat(state.is_plan_mode()).isTrue();
    }

    @Test
    void exit_plan_mode_dispatches_and_clears_flag(@TempDir Path workspace) throws IOException {
        PlanModeState state = new PlanModeState();
        state.set_plan_mode(true);
        ToolDispatcher dispatcher = new ToolDispatcher(
                workspace,
                new GlobalToolRegistry(MvpToolSpecs.mvp_tool_specs()),
                new TodoStore(),
                state,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        ToolResult result = dispatcher.execute("ExitPlanMode", MAPPER.createObjectNode());

        assertThat(result.is_error()).isFalse();
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("plan_mode").asBoolean()).isFalse();
        assertThat(state.is_plan_mode()).isFalse();
    }

    @Test
    void send_user_message_dispatches_and_writes_to_sink(@TempDir Path workspace) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ToolDispatcher dispatcher = new ToolDispatcher(
                workspace,
                new GlobalToolRegistry(MvpToolSpecs.mvp_tool_specs()),
                new TodoStore(),
                new PlanModeState(),
                new PrintStream(sink, true, StandardCharsets.UTF_8));

        ObjectNode input = MAPPER.createObjectNode();
        input.put("message", "hello user");

        ToolResult result = dispatcher.execute("SendUserMessage", input);

        assertThat(result.is_error()).isFalse();
        assertThat(sink.toString(StandardCharsets.UTF_8)).contains("hello user");
        JsonNode payload = MAPPER.readTree(result.output());
        assertThat(payload.get("message").asText()).isEqualTo("hello user");
    }

    @Test
    void unknown_tool_throws_unsupported_tool_exception(@TempDir Path workspace) {
        assertThatThrownBy(() -> dispatcher_for(workspace).execute("DoesNotExist", MAPPER.createObjectNode()))
                .isInstanceOf(UnsupportedToolException.class)
                .hasMessageContaining("DoesNotExist");
    }

    @Test
    void tool_dispatcher_serializes_outputs_as_json(@TempDir Path workspace) throws IOException {
        Path file = workspace.resolve("round.txt");
        Files.writeString(file, "alpha\nbeta\ngamma");
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "round.txt");

        ToolResult result = dispatcher_for(workspace).execute("read_file", input);

        // Round-trip the dispatcher's JSON output back through the mapper to confirm it is a
        // valid JSON document with the expected structure.
        JsonNode roundTripped = MAPPER.readTree(result.output());
        assertThat(roundTripped.isObject()).isTrue();
        assertThat(roundTripped.get("kind").asText()).isEqualTo("text");
        assertThat(roundTripped.get("file").get("content").asText()).isEqualTo("alpha\nbeta\ngamma");
        assertThat(roundTripped.get("file").get("total_lines").asInt()).isEqualTo(3);
    }

    @Test
    void error_during_dispatch_returns_is_error_result(@TempDir Path workspace) throws IOException {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "does-not-exist.txt");

        ToolResult result = dispatcher_for(workspace).execute("read_file", input);

        assertThat(result.is_error()).isTrue();
        assertThat(result.output()).isNotEmpty();
    }
}
