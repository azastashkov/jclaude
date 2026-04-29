package org.jclaude.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionStoreTest {

    @Test
    void path_for_returns_jsonl_under_default_subdir(@TempDir Path tempDir) {
        SessionStore store = SessionStore.in_root(tempDir);
        Path path = store.path_for("session-42");
        assertThat(path)
                .isEqualTo(tempDir.resolve(".jclaude").resolve("sessions").resolve("session-42.jsonl"));
    }

    @Test
    void list_sessions_returns_empty_when_directory_missing(@TempDir Path tempDir) {
        SessionStore store = SessionStore.in_root(tempDir);
        assertThat(store.list_sessions()).isEmpty();
    }

    @Test
    void list_sessions_skips_non_jsonl_entries(@TempDir Path tempDir) throws IOException {
        SessionStore store = SessionStore.in_root(tempDir);
        Path dir = store.sessions_dir();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("alpha.jsonl"), "");
        Files.writeString(dir.resolve("beta.jsonl"), "");
        Files.writeString(dir.resolve("ignore.txt"), "skip me");

        assertThat(store.list_sessions())
                .extracting(p -> p.getFileName().toString())
                .containsExactly("alpha.jsonl", "beta.jsonl");
    }

    @Test
    void latest_session_returns_most_recently_modified(@TempDir Path tempDir) throws IOException {
        SessionStore store = SessionStore.in_root(tempDir);
        Path dir = store.sessions_dir();
        Files.createDirectories(dir);
        Path older = dir.resolve("older.jsonl");
        Path newer = dir.resolve("newer.jsonl");
        Files.writeString(older, "");
        Files.writeString(newer, "");
        Files.setLastModifiedTime(older, FileTime.fromMillis(1));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(1_000_000));

        assertThat(store.latest_session()).contains(newer);
    }
}
