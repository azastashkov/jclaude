package org.jclaude.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jclaude.runtime.permissions.PermissionMode;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PermissionModeOption#parse(String)}. Mirrors the {@code
 * normalizes_supported_permission_modes} test at crates/rusty-claude-cli/src/main.rs:12270 and the
 * {@code parses_permission_mode_flag} test at line 9939.
 */
final class PermissionModeOptionTest {

    @Test
    void parse_accepts_canonical_kebab_case_names() {
        // Mirrors `normalizes_supported_permission_modes` —
        // crates/rusty-claude-cli/src/main.rs:12270.
        assertThat(PermissionModeOption.parse("read-only")).isEqualTo(PermissionModeOption.READ_ONLY);
        assertThat(PermissionModeOption.parse("workspace-write")).isEqualTo(PermissionModeOption.WORKSPACE_WRITE);
        assertThat(PermissionModeOption.parse("danger-full-access")).isEqualTo(PermissionModeOption.DANGER_FULL_ACCESS);
    }

    @Test
    void parse_is_case_insensitive_and_trims_whitespace() {
        assertThat(PermissionModeOption.parse("  READ-ONLY  ")).isEqualTo(PermissionModeOption.READ_ONLY);
        assertThat(PermissionModeOption.parse("Workspace-Write")).isEqualTo(PermissionModeOption.WORKSPACE_WRITE);
    }

    @Test
    void parse_returns_default_read_only_for_null() {
        assertThat(PermissionModeOption.parse(null)).isEqualTo(PermissionModeOption.READ_ONLY);
    }

    @Test
    void parse_rejects_unknown_modes_with_helpful_message() {
        assertThatThrownBy(() -> PermissionModeOption.parse("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown permission mode")
                .hasMessageContaining("nope");
    }

    @Test
    void runtime_returns_matching_runtime_permission_mode() {
        // Mirrors the bridge between CLI string forms and runtime enum at
        // crates/rusty-claude-cli/src/main.rs:9580 (default_permission_mode_uses_project_config).
        assertThat(PermissionModeOption.READ_ONLY.runtime()).isEqualTo(PermissionMode.READ_ONLY);
        assertThat(PermissionModeOption.WORKSPACE_WRITE.runtime()).isEqualTo(PermissionMode.WORKSPACE_WRITE);
        assertThat(PermissionModeOption.DANGER_FULL_ACCESS.runtime()).isEqualTo(PermissionMode.DANGER_FULL_ACCESS);
    }

    @Test
    void wire_returns_canonical_kebab_case_string() {
        assertThat(PermissionModeOption.READ_ONLY.wire()).isEqualTo("read-only");
        assertThat(PermissionModeOption.WORKSPACE_WRITE.wire()).isEqualTo("workspace-write");
        assertThat(PermissionModeOption.DANGER_FULL_ACCESS.wire()).isEqualTo("danger-full-access");
    }
}
