package org.jclaude.runtime.sessioncontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionControlStoreTest {

    @Test
    void from_cwd_creates_sessions_directory(@TempDir Path tmp) {
        SessionControlStore store = SessionControlStore.from_cwd(tmp);

        assertThat(store.sessions_dir()).exists();
        assertThat(store.workspace_root().toString()).contains(tmp.toString().replaceFirst("^/private", ""));
    }

    @Test
    void from_data_dir_namespaces_by_workspace(@TempDir Path tmp) {
        Path data = tmp.resolve("data");
        SessionControlStore store_a = SessionControlStore.from_data_dir(data, tmp.resolve("a"));
        SessionControlStore store_b = SessionControlStore.from_data_dir(data, tmp.resolve("b"));

        assertThat(store_a.sessions_dir()).isNotEqualTo(store_b.sessions_dir());
    }

    @Test
    void create_handle_uses_primary_extension(@TempDir Path tmp) {
        SessionControlStore store = SessionControlStore.from_cwd(tmp);

        SessionHandle handle = store.create_handle("session-1");

        assertThat(handle.id()).isEqualTo("session-1");
        assertThat(handle.path().getFileName().toString()).isEqualTo("session-1.json");
    }

    @Test
    void list_sessions_returns_all_session_files(@TempDir Path tmp) throws Exception {
        SessionControlStore store = SessionControlStore.from_cwd(tmp);
        Files.writeString(store.create_handle("a").path(), "{}");
        Files.writeString(store.create_handle("b").path(), "{}");

        assertThat(store.list_sessions()).hasSize(2);
    }

    @Test
    void resolve_alias_returns_latest_session(@TempDir Path tmp) throws Exception {
        SessionControlStore store = SessionControlStore.from_cwd(tmp);
        Path first = store.create_handle("first").path();
        Files.writeString(first, "{}");
        Thread.sleep(20);
        Path second = store.create_handle("second").path();
        Files.writeString(second, "{}");

        SessionHandle latest = store.resolve_reference("@latest");

        assertThat(latest.id()).isEqualTo("second");
    }

    @Test
    void empty_store_latest_throws(@TempDir Path tmp) {
        SessionControlStore store = SessionControlStore.from_cwd(tmp);

        assertThatThrownBy(store::latest_session).isInstanceOf(SessionControlError.class);
    }

    @Test
    void is_session_reference_alias_recognises_latest_and_alias() {
        assertThat(SessionControlStore.is_session_reference_alias("@latest")).isTrue();
        assertThat(SessionControlStore.is_session_reference_alias("latest")).isTrue();
        assertThat(SessionControlStore.is_session_reference_alias("session-1")).isFalse();
    }

    @Test
    void session_store_from_cwd_canonicalizes_equivalent_paths(@TempDir Path tmp) throws Exception {
        Path realDir = tmp.resolve("real-workspace");
        Files.createDirectories(realDir);

        Path canonical = realDir.toRealPath();

        SessionControlStore from_raw = SessionControlStore.from_cwd(realDir);
        SessionControlStore from_canonical = SessionControlStore.from_cwd(canonical);

        assertThat(from_raw.sessions_dir()).isEqualTo(from_canonical.sessions_dir());
    }

    @Test
    void session_store_from_cwd_isolates_sessions_by_workspace(@TempDir Path tmp) throws Exception {
        Path workspace_a = tmp.resolve("repo-alpha");
        Path workspace_b = tmp.resolve("repo-beta");
        Files.createDirectories(workspace_a);
        Files.createDirectories(workspace_b);

        SessionControlStore store_a = SessionControlStore.from_cwd(workspace_a);
        SessionControlStore store_b = SessionControlStore.from_cwd(workspace_b);

        Files.writeString(store_a.create_handle("a-session").path(), "{}");
        Files.writeString(store_b.create_handle("b-session").path(), "{}");

        assertThat(store_a.list_sessions()).hasSize(1);
        assertThat(store_b.list_sessions()).hasSize(1);
        assertThat(store_a.sessions_dir()).isNotEqualTo(store_b.sessions_dir());
    }

    @Test
    void session_store_create_and_load_round_trip(@TempDir Path tmp) throws Exception {
        SessionControlStore store = SessionControlStore.from_cwd(tmp);
        SessionHandle handle = store.create_handle("my-session");

        Files.writeString(handle.path(), "{\"messages\":[]}");

        SessionHandle resolved = store.resolve_reference("my-session");
        assertThat(resolved.id()).isEqualTo("my-session");
        assertThat(resolved.path()).isEqualTo(handle.path());
        assertThat(Files.readString(resolved.path())).contains("messages");
    }

    @Test
    void session_store_latest_and_resolve_reference(@TempDir Path tmp) throws Exception {
        SessionControlStore store = SessionControlStore.from_cwd(tmp);
        Files.writeString(store.create_handle("a").path(), "{}");
        Thread.sleep(20);
        Files.writeString(store.create_handle("b").path(), "{}");

        assertThat(store.latest_session().id()).isEqualTo("b");
        assertThat(store.resolve_reference("@latest").id()).isEqualTo("b");
        assertThat(store.resolve_reference("a").id()).isEqualTo("a");
    }

    @Test
    void session_store_fork_stays_in_same_namespace(@TempDir Path tmp) throws Exception {
        // Java port: forking is just creating a new handle in the same store.
        SessionControlStore store = SessionControlStore.from_cwd(tmp);
        SessionHandle parent = store.create_handle("parent");
        Files.writeString(parent.path(), "{}");

        SessionHandle fork = store.create_handle("fork");
        Files.writeString(fork.path(), "{}");

        assertThat(fork.path().getParent()).isEqualTo(parent.path().getParent());
        assertThat(store.list_sessions()).hasSize(2);
    }

    @Test
    void workspace_fingerprint_is_deterministic_and_differs_per_path(@TempDir Path tmp) throws Exception {
        Path path_a = tmp.resolve("worktree-alpha");
        Path path_b = tmp.resolve("worktree-beta");
        Files.createDirectories(path_a);
        Files.createDirectories(path_b);

        SessionControlStore from_a1 = SessionControlStore.from_cwd(path_a);
        SessionControlStore from_a2 = SessionControlStore.from_cwd(path_a);
        SessionControlStore from_b = SessionControlStore.from_cwd(path_b);

        assertThat(from_a1.sessions_dir()).isEqualTo(from_a2.sessions_dir());
        assertThat(from_a1.sessions_dir()).isNotEqualTo(from_b.sessions_dir());
    }
}
