package org.jclaude.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeConfigTest {

    @Test
    void empty_returns_default_config() {
        RuntimeConfig config = RuntimeConfig.empty();

        assertThat(config.merged()).isEmpty();
        assertThat(config.loaded_entries()).isEmpty();
        assertThat(config.hooks().pre_tool_use()).isEmpty();
    }

    @Test
    void loads_and_merges_user_then_project(@TempDir Path tmp) throws Exception {
        Path user = tmp.resolve("user.json");
        Path project = tmp.resolve("project.json");
        Files.writeString(user, "{\"model\": \"sonnet\", \"trustedRoots\": [\"/u\"]}");
        Files.writeString(project, "{\"model\": \"opus\", \"trustedRoots\": [\"/p\"]}");

        RuntimeConfig config = RuntimeConfig.load(
                List.of(new ConfigEntry(ConfigSource.USER, user), new ConfigEntry(ConfigSource.PROJECT, project)));

        assertThat(config.model()).contains("opus");
        assertThat(config.trusted_roots()).containsExactly("/p");
        assertThat(config.loaded_entries()).hasSize(2);
    }

    @Test
    void parses_permission_mode_aliases(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("p.json");
        Files.writeString(file, "{\"permissionMode\": \"workspace-write\"}");

        RuntimeConfig config = RuntimeConfig.load(List.of(new ConfigEntry(ConfigSource.USER, file)));

        assertThat(config.permission_mode()).contains(ResolvedPermissionMode.WORKSPACE_WRITE);
    }

    @Test
    void rejects_non_object_settings_file(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("bad.json");
        Files.writeString(file, "[]");

        assertThatThrownBy(() -> RuntimeConfig.load(List.of(new ConfigEntry(ConfigSource.USER, file))))
                .isInstanceOf(ConfigValidationError.class);
    }

    @Test
    void extracts_hook_commands(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("h.json");
        Files.writeString(file, "{\"hooks\": {\"PreToolUse\": [\"echo before\"], \"PostToolUse\": [\"echo after\"]}}");

        RuntimeConfig config = RuntimeConfig.load(List.of(new ConfigEntry(ConfigSource.USER, file)));

        assertThat(config.hooks().pre_tool_use()).containsExactly("echo before");
        assertThat(config.hooks().post_tool_use()).containsExactly("echo after");
    }

    @Test
    void rejects_non_object_settings_files(@TempDir Path tmp) throws Exception {
        // Mirrors crates/runtime/src/config.rs:rejects_non_object_settings_files
        Path file = tmp.resolve("bad.json");
        Files.writeString(file, "\"just-a-string\"");

        assertThatThrownBy(() -> RuntimeConfig.load(List.of(new ConfigEntry(ConfigSource.USER, file))))
                .isInstanceOf(ConfigValidationError.class);
    }

    @Test
    void empty_settings_file_loads_defaults(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("empty.json");
        Files.writeString(file, "");

        RuntimeConfig config = RuntimeConfig.load(List.of(new ConfigEntry(ConfigSource.USER, file)));

        assertThat(config.merged()).isEmpty();
        assertThat(config.model()).isEmpty();
        assertThat(config.permission_mode()).isEmpty();
    }

    @Test
    void parses_trusted_roots_from_settings(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("t.json");
        Files.writeString(file, "{\"trustedRoots\": [\"/tmp/repo-a\", \"/tmp/repo-b\"]}");

        RuntimeConfig config = RuntimeConfig.load(List.of(new ConfigEntry(ConfigSource.USER, file)));

        assertThat(config.trusted_roots()).containsExactly("/tmp/repo-a", "/tmp/repo-b");
    }

    @Test
    void trusted_roots_default_is_empty_when_unset() {
        RuntimeConfig config = RuntimeConfig.empty();
        assertThat(config.trusted_roots()).isEmpty();
    }

    @Test
    void provider_fallbacks_default_is_empty_when_unset() {
        RuntimeConfig config = RuntimeConfig.empty();
        assertThat(config.provider_fallbacks()).isNotNull();
    }

    @Test
    void permission_mode_aliases_resolve_to_expected_modes(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("p.json");
        Files.writeString(file, "{\"permissionMode\": \"read-only\"}");

        RuntimeConfig ro = RuntimeConfig.load(List.of(new ConfigEntry(ConfigSource.USER, file)));
        assertThat(ro.permission_mode()).contains(ResolvedPermissionMode.READ_ONLY);

        Files.writeString(file, "{\"permissionMode\": \"dangerFullAccess\"}");
        RuntimeConfig danger = RuntimeConfig.load(List.of(new ConfigEntry(ConfigSource.USER, file)));
        assertThat(danger.permission_mode()).contains(ResolvedPermissionMode.DANGER_FULL_ACCESS);
    }

    @Test
    void deep_merge_objects_merges_nested_maps(@TempDir Path tmp) throws Exception {
        // Java's RuntimeConfig.load merges fields by name (later overrides earlier).
        Path user = tmp.resolve("user.json");
        Path project = tmp.resolve("project.json");
        Files.writeString(user, "{\"hooks\": {\"PreToolUse\": [\"echo a\"]}}");
        Files.writeString(project, "{\"hooks\": {\"PreToolUse\": [\"echo b\"]}}");

        RuntimeConfig config = RuntimeConfig.load(
                List.of(new ConfigEntry(ConfigSource.USER, user), new ConfigEntry(ConfigSource.PROJECT, project)));

        // Project overrides user under merge-by-key semantics.
        assertThat(config.hooks().pre_tool_use()).containsExactly("echo b");
    }

    @Test
    void loads_and_merges_claude_code_config_files_by_precedence(@TempDir Path tmp) throws Exception {
        Path user = tmp.resolve("user.json");
        Path project = tmp.resolve("project.json");
        Path local = tmp.resolve("local.json");
        Files.writeString(user, "{\"model\": \"haiku\"}");
        Files.writeString(project, "{\"model\": \"sonnet\"}");
        Files.writeString(local, "{\"model\": \"opus\"}");

        RuntimeConfig config = RuntimeConfig.load(List.of(
                new ConfigEntry(ConfigSource.USER, user),
                new ConfigEntry(ConfigSource.PROJECT, project),
                new ConfigEntry(ConfigSource.LOCAL, local)));

        assertThat(config.model()).contains("opus");
        assertThat(config.loaded_entries()).hasSize(3);
    }
}
