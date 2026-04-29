package org.jclaude.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.jclaude.runtime.usage.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionTest {

    private static final ObjectMapper RAW_JSON = new ObjectMapper();

    @Test
    void session_timestamps_are_monotonic_under_tight_loops() {
        long first = Session.current_time_millis();
        long second = Session.current_time_millis();
        long third = Session.current_time_millis();

        assertThat(first).isLessThan(second);
        assertThat(second).isLessThan(third);
    }

    @Test
    void persists_and_restores_session_jsonl(@TempDir Path tempDir) throws IOException {
        Session session = Session.create();
        session.push_user_text("hello");
        session.append_message(ConversationMessage.assistant_with_usage(
                List.of(new ContentBlock.Text("thinking"), new ContentBlock.ToolUse("tool-1", "bash", "echo hi")),
                new TokenUsage(10, 4, 1, 2)));
        session.append_message(ConversationMessage.tool_result("tool-1", "bash", "hi", false));

        Path path = temp_session_path(tempDir, "jsonl");
        session.save_to_path(path);
        Session restored = Session.load_from_path(path);
        Files.delete(path);

        assertThat(restored).isEqualTo(session);
        assertThat(restored.messages().get(2).role()).isEqualTo(MessageRole.TOOL);
        assertThat(restored.messages().get(1).usage().total_tokens()).isEqualTo(17);
        assertThat(restored.session_id()).isEqualTo(session.session_id());
    }

    @Test
    void loads_legacy_session_json_object(@TempDir Path tempDir) throws IOException {
        Path path = temp_session_path(tempDir, "legacy");
        ObjectNode legacy = RAW_JSON.createObjectNode();
        legacy.put("version", 1);
        ArrayNode messages = legacy.putArray("messages");
        messages.add(message_node(ConversationMessage.user_text("legacy")));
        Files.writeString(path, RAW_JSON.writeValueAsString(legacy));

        Session restored = Session.load_from_path(path);
        Files.delete(path);

        assertThat(restored.messages()).hasSize(1);
        assertThat(restored.messages().get(0)).isEqualTo(ConversationMessage.user_text("legacy"));
        assertThat(restored.session_id()).isNotEmpty();
    }

    @Test
    void appends_messages_to_persisted_jsonl_session(@TempDir Path tempDir) throws IOException {
        Path path = temp_session_path(tempDir, "append");
        Session session = Session.create().with_persistence_path(path);
        session.save_to_path(path);
        session.push_user_text("hi");
        session.append_message(ConversationMessage.assistant(List.of(new ContentBlock.Text("hello"))));

        Session restored = Session.load_from_path(path);
        Files.delete(path);

        assertThat(restored.messages()).hasSize(2);
        assertThat(restored.messages().get(0)).isEqualTo(ConversationMessage.user_text("hi"));
    }

    @Test
    void persists_compaction_metadata(@TempDir Path tempDir) throws IOException {
        Path path = temp_session_path(tempDir, "compaction");
        Session session = Session.create();
        session.push_user_text("before");
        session.record_compaction(new SessionCompaction(0, 4, "summarized earlier work"));
        session.save_to_path(path);

        Session restored = Session.load_from_path(path);
        Files.delete(path);

        SessionCompaction compaction =
                restored.compaction().orElseThrow(() -> new AssertionError("compaction metadata"));
        assertThat(compaction.count()).isEqualTo(1);
        assertThat(compaction.removed_message_count()).isEqualTo(4);
        assertThat(compaction.summary()).contains("summarized");
    }

    @Test
    void forks_sessions_with_branch_metadata_and_persists_it(@TempDir Path tempDir) throws IOException {
        Path path = temp_session_path(tempDir, "fork");
        Session session = Session.create();
        session.push_user_text("before fork");

        Session forked = session.forked("investigation").with_persistence_path(path);
        forked.save_to_path(path);

        Session restored = Session.load_from_path(path);
        Files.delete(path);

        assertThat(restored.session_id()).isNotEqualTo(session.session_id());
        assertThat(restored.fork()).contains(new SessionFork(session.session_id(), "investigation"));
        assertThat(restored.messages()).isEqualTo(forked.messages());
    }

    @Test
    void rotates_and_cleans_up_large_session_logs(@TempDir Path tempDir) throws IOException {
        // given
        Path path = temp_session_path(tempDir, "rotation");
        int oversized_length = (int) (Session.ROTATE_AFTER_BYTES + 10);
        Files.writeString(path, "x".repeat(oversized_length));

        // when
        Session.rotate_session_file_if_needed(path);

        // then
        assertThat(Files.exists(path))
                .as("original path should be rotated away before rewrite")
                .isFalse();

        for (int i = 0; i < 5; i++) {
            Path rotated = Session.rotated_log_path(path);
            Files.writeString(rotated, "old");
        }
        Session.cleanup_rotated_logs(path);

        List<Path> rotated_after = rotation_files(path);
        assertThat(rotated_after.size()).isLessThanOrEqualTo(Session.MAX_ROTATED_FILES);
        for (Path rotated : rotated_after) {
            Files.delete(rotated);
        }
    }

    @Test
    void rejects_jsonl_record_without_type(@TempDir Path tempDir) throws IOException {
        // given
        Path path = write_temp_session_file(
                tempDir,
                "missing-type",
                "{\"message\":{\"role\":\"user\",\"blocks\":[{\"type\":\"text\",\"text\":\"hello\"}]}}");

        // when / then
        assertThatThrownBy(() -> Session.load_from_path(path))
                .isInstanceOf(SessionError.class)
                .hasMessageContaining("missing type");
        Files.delete(path);
    }

    @Test
    void rejects_jsonl_message_record_without_message_payload(@TempDir Path tempDir) throws IOException {
        // given
        Path path = write_temp_session_file(tempDir, "missing-message", "{\"type\":\"message\"}");

        // when / then
        assertThatThrownBy(() -> Session.load_from_path(path))
                .isInstanceOf(SessionError.class)
                .hasMessageContaining("missing message");
        Files.delete(path);
    }

    @Test
    void rejects_jsonl_record_with_unknown_type(@TempDir Path tempDir) throws IOException {
        // given
        Path path = write_temp_session_file(tempDir, "unknown-type", "{\"type\":\"mystery\"}");

        // when / then
        assertThatThrownBy(() -> Session.load_from_path(path))
                .isInstanceOf(SessionError.class)
                .hasMessageContaining("unsupported JSONL record type");
        Files.delete(path);
    }

    @Test
    void rejects_legacy_session_json_without_messages() {
        // given
        ObjectNode legacy = RAW_JSON.createObjectNode();
        legacy.put("version", 1);

        // when / then
        assertThatThrownBy(() -> Session.from_json(legacy))
                .isInstanceOf(SessionError.class)
                .hasMessageContaining("missing messages");
    }

    @Test
    void normalizes_blank_fork_branch_name_to_none() {
        // given
        Session session = Session.create();

        // when
        Session forked = session.forked("   ");

        // then
        SessionFork fork = forked.fork().orElseThrow(() -> new AssertionError("fork metadata"));
        assertThat(fork.branch_name()).isNull();
    }

    @Test
    void rejects_unknown_content_block_type() throws IOException {
        // given a JSONL message containing an unknown block type so the session
        // loader exercises ContentBlock parsing through the public API.
        ObjectNode block = RAW_JSON.createObjectNode();
        block.put("type", "unknown");

        // when / then
        assertThatThrownBy(() -> {
                    ObjectNode message = RAW_JSON.createObjectNode();
                    message.put("role", "user");
                    ArrayNode blocks = message.putArray("blocks");
                    blocks.add(block);
                    ObjectNode envelope = RAW_JSON.createObjectNode();
                    envelope.put("type", "message");
                    envelope.set("message", message);
                    Path tmp = Files.createTempFile("unknown-block", ".jsonl");
                    try {
                        Files.writeString(tmp, RAW_JSON.writeValueAsString(envelope) + "\n");
                        Session.load_from_path(tmp);
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                })
                .isInstanceOf(SessionError.class)
                .hasMessageContaining("unsupported block type");
    }

    @Test
    void persists_workspace_root_round_trip_and_forks_inherit_it(@TempDir Path tempDir) throws IOException {
        // given
        Path path = temp_session_path(tempDir, "workspace-root");
        Path workspace_root = tempDir.resolve("b4-phantom-diag");
        Files.createDirectories(workspace_root);
        Session session = Session.create().with_workspace_root(workspace_root);
        session.push_user_text("write to the right cwd");

        // when
        session.save_to_path(path);
        Session restored = Session.load_from_path(path);
        Session forked = restored.forked("phantom-diag");
        Files.delete(path);

        // then
        assertThat(restored.workspace_root()).contains(workspace_root);
        assertThat(forked.workspace_root()).contains(workspace_root);
    }

    private Path temp_session_path(Path tempDir, String label) {
        long nanos = System.nanoTime();
        return tempDir.resolve("runtime-session-" + label + "-" + nanos + ".json");
    }

    private Path write_temp_session_file(Path tempDir, String label, String contents) throws IOException {
        Path path = temp_session_path(tempDir, label);
        Files.writeString(path, contents + "\n");
        return path;
    }

    private List<Path> rotation_files(Path path) throws IOException {
        Path nameOnly = path.getFileName();
        String full = nameOnly == null ? "session" : nameOnly.toString();
        int dot = full.lastIndexOf('.');
        String stem = dot <= 0 ? full : full.substring(0, dot);
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(parent)) {
            List<Path> entries = new ArrayList<>();
            stream.filter(entry -> {
                        Path name = entry.getFileName();
                        if (name == null) {
                            return false;
                        }
                        String n = name.toString();
                        if (!n.startsWith(stem + ".rot-")) {
                            return false;
                        }
                        int extDot = n.lastIndexOf('.');
                        if (extDot < 0) {
                            return false;
                        }
                        return n.substring(extDot + 1).equalsIgnoreCase("jsonl");
                    })
                    .forEach(entries::add);
            entries.sort(Comparator.comparing(Path::toString));
            return entries;
        }
    }

    private JsonNode message_node(ConversationMessage message) {
        ObjectNode object = RAW_JSON.createObjectNode();
        object.put("role", message.role().wire());
        ArrayNode blocks = object.putArray("blocks");
        for (ContentBlock block : message.blocks()) {
            ObjectNode b = RAW_JSON.createObjectNode();
            if (block instanceof ContentBlock.Text text) {
                b.put("type", "text");
                b.put("text", text.text());
            }
            blocks.add(b);
        }
        return object;
    }
}
