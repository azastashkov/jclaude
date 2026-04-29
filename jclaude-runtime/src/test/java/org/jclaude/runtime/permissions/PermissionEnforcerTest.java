package org.jclaude.runtime.permissions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PermissionEnforcerTest {

    private static PermissionEnforcer make_enforcer(PermissionMode mode) {
        return new PermissionEnforcer(new PermissionPolicy(mode));
    }

    @Test
    void allow_mode_permits_everything() {
        PermissionEnforcer enforcer = make_enforcer(PermissionMode.ALLOW);
        assertThat(enforcer.is_allowed("bash", "")).isTrue();
        assertThat(enforcer.is_allowed("write_file", "")).isTrue();
        assertThat(enforcer.is_allowed("edit_file", "")).isTrue();
        assertThat(enforcer.check_file_write("/outside/path", "/workspace")).isEqualTo(new EnforcementResult.Allowed());
        assertThat(enforcer.check_bash("rm -rf /")).isEqualTo(new EnforcementResult.Allowed());
    }

    @Test
    void read_only_denies_writes() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.READ_ONLY)
                .with_tool_requirement("read_file", PermissionMode.READ_ONLY)
                .with_tool_requirement("grep_search", PermissionMode.READ_ONLY)
                .with_tool_requirement("write_file", PermissionMode.WORKSPACE_WRITE);

        PermissionEnforcer enforcer = new PermissionEnforcer(policy);
        assertThat(enforcer.is_allowed("read_file", "")).isTrue();
        assertThat(enforcer.is_allowed("grep_search", "")).isTrue();

        assertThat(enforcer.check("write_file", "")).isInstanceOf(EnforcementResult.Denied.class);
        assertThat(enforcer.check_file_write("/workspace/file.rs", "/workspace"))
                .isInstanceOf(EnforcementResult.Denied.class);
    }

    @Test
    void read_only_allows_read_commands() {
        PermissionEnforcer enforcer = make_enforcer(PermissionMode.READ_ONLY);
        assertThat(enforcer.check_bash("cat src/main.rs")).isEqualTo(new EnforcementResult.Allowed());
        assertThat(enforcer.check_bash("grep -r 'pattern' .")).isEqualTo(new EnforcementResult.Allowed());
        assertThat(enforcer.check_bash("ls -la")).isEqualTo(new EnforcementResult.Allowed());
    }

    @Test
    void read_only_denies_write_commands() {
        PermissionEnforcer enforcer = make_enforcer(PermissionMode.READ_ONLY);
        assertThat(enforcer.check_bash("rm file.txt")).isInstanceOf(EnforcementResult.Denied.class);
    }

    @Test
    void workspace_write_allows_within_workspace() {
        PermissionEnforcer enforcer = make_enforcer(PermissionMode.WORKSPACE_WRITE);
        assertThat(enforcer.check_file_write("/workspace/src/main.rs", "/workspace"))
                .isEqualTo(new EnforcementResult.Allowed());
    }

    @Test
    void workspace_write_denies_outside_workspace() {
        PermissionEnforcer enforcer = make_enforcer(PermissionMode.WORKSPACE_WRITE);
        assertThat(enforcer.check_file_write("/etc/passwd", "/workspace")).isInstanceOf(EnforcementResult.Denied.class);
    }

    @Test
    void prompt_mode_denies_without_prompter() {
        PermissionEnforcer enforcer = make_enforcer(PermissionMode.PROMPT);
        assertThat(enforcer.check_bash("echo test")).isInstanceOf(EnforcementResult.Denied.class);
        assertThat(enforcer.check_file_write("/workspace/file.rs", "/workspace"))
                .isInstanceOf(EnforcementResult.Denied.class);
    }

    @Test
    void workspace_boundary_check() {
        assertThat(PermissionEnforcer.is_within_workspace("/workspace/src/main.rs", "/workspace"))
                .isTrue();
        assertThat(PermissionEnforcer.is_within_workspace("/workspace", "/workspace"))
                .isTrue();
        assertThat(PermissionEnforcer.is_within_workspace("/etc/passwd", "/workspace"))
                .isFalse();
        assertThat(PermissionEnforcer.is_within_workspace("/workspacex/hack", "/workspace"))
                .isFalse();
    }

    @Test
    void read_only_command_heuristic() {
        assertThat(PermissionEnforcer.is_read_only_command("cat file.txt")).isTrue();
        assertThat(PermissionEnforcer.is_read_only_command("grep pattern file")).isTrue();
        assertThat(PermissionEnforcer.is_read_only_command("git log --oneline")).isTrue();
        assertThat(PermissionEnforcer.is_read_only_command("rm file.txt")).isFalse();
        assertThat(PermissionEnforcer.is_read_only_command("echo test > file.txt"))
                .isFalse();
        assertThat(PermissionEnforcer.is_read_only_command("sed -i 's/a/b/' file"))
                .isFalse();
    }

    @Test
    void active_mode_returns_policy_mode() {
        PermissionMode[] modes = {
            PermissionMode.READ_ONLY,
            PermissionMode.WORKSPACE_WRITE,
            PermissionMode.DANGER_FULL_ACCESS,
            PermissionMode.PROMPT,
            PermissionMode.ALLOW
        };

        for (PermissionMode mode : modes) {
            assertThat(make_enforcer(mode).active_mode()).isEqualTo(mode);
        }
    }

    @Test
    void danger_full_access_permits_file_writes_and_bash() {
        PermissionEnforcer enforcer = make_enforcer(PermissionMode.DANGER_FULL_ACCESS);

        assertThat(enforcer.check_file_write("/outside/workspace/file.txt", "/workspace"))
                .isEqualTo(new EnforcementResult.Allowed());
        assertThat(enforcer.check_bash("rm -rf /tmp/scratch")).isEqualTo(new EnforcementResult.Allowed());
    }

    @Test
    void check_denied_payload_contains_tool_and_modes() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.READ_ONLY)
                .with_tool_requirement("write_file", PermissionMode.WORKSPACE_WRITE);
        PermissionEnforcer enforcer = new PermissionEnforcer(policy);

        EnforcementResult result = enforcer.check("write_file", "{}");

        assertThat(result).isInstanceOf(EnforcementResult.Denied.class);
        EnforcementResult.Denied denied = (EnforcementResult.Denied) result;
        assertThat(denied.tool()).isEqualTo("write_file");
        assertThat(denied.active_mode()).isEqualTo("read-only");
        assertThat(denied.required_mode()).isEqualTo("workspace-write");
        assertThat(denied.reason()).contains("requires workspace-write permission");
    }

    @Test
    void workspace_write_relative_path_resolved() {
        PermissionEnforcer enforcer = make_enforcer(PermissionMode.WORKSPACE_WRITE);
        assertThat(enforcer.check_file_write("src/main.rs", "/workspace")).isEqualTo(new EnforcementResult.Allowed());
    }

    @Test
    void workspace_root_with_trailing_slash() {
        PermissionEnforcer enforcer = make_enforcer(PermissionMode.WORKSPACE_WRITE);
        assertThat(enforcer.check_file_write("/workspace/src/main.rs", "/workspace/"))
                .isEqualTo(new EnforcementResult.Allowed());
    }

    @Test
    void workspace_root_equality() {
        assertThat(PermissionEnforcer.is_within_workspace("/workspace", "/workspace/"))
                .isTrue();
    }

    @Test
    void bash_heuristic_full_path_prefix() {
        assertThat(PermissionEnforcer.is_read_only_command("/usr/bin/cat Cargo.toml"))
                .isTrue();
        assertThat(PermissionEnforcer.is_read_only_command("/usr/local/bin/git status"))
                .isTrue();
    }

    @Test
    void bash_heuristic_redirects_block_read_only_commands() {
        assertThat(PermissionEnforcer.is_read_only_command("cat Cargo.toml > out.txt"))
                .isFalse();
        assertThat(PermissionEnforcer.is_read_only_command("echo test >> out.txt"))
                .isFalse();
    }

    @Test
    void bash_heuristic_in_place_flag_blocks() {
        assertThat(PermissionEnforcer.is_read_only_command("python -i script.py"))
                .isFalse();
        assertThat(PermissionEnforcer.is_read_only_command("sed --in-place 's/a/b/' file.txt"))
                .isFalse();
    }

    @Test
    void bash_heuristic_empty_command() {
        assertThat(PermissionEnforcer.is_read_only_command("")).isFalse();
        assertThat(PermissionEnforcer.is_read_only_command("   ")).isFalse();
    }

    @Test
    void prompt_mode_check_bash_denied_payload_fields() {
        PermissionEnforcer enforcer = make_enforcer(PermissionMode.PROMPT);

        EnforcementResult result = enforcer.check_bash("git status");

        assertThat(result).isInstanceOf(EnforcementResult.Denied.class);
        EnforcementResult.Denied denied = (EnforcementResult.Denied) result;
        assertThat(denied.tool()).isEqualTo("bash");
        assertThat(denied.active_mode()).isEqualTo("prompt");
        assertThat(denied.required_mode()).isEqualTo("danger-full-access");
        assertThat(denied.reason()).isEqualTo("bash requires confirmation in prompt mode");
    }

    @Test
    void read_only_check_file_write_denied_payload() {
        PermissionEnforcer enforcer = make_enforcer(PermissionMode.READ_ONLY);

        EnforcementResult result = enforcer.check_file_write("/workspace/file.txt", "/workspace");

        assertThat(result).isInstanceOf(EnforcementResult.Denied.class);
        EnforcementResult.Denied denied = (EnforcementResult.Denied) result;
        assertThat(denied.tool()).isEqualTo("write_file");
        assertThat(denied.active_mode()).isEqualTo("read-only");
        assertThat(denied.required_mode()).isEqualTo("workspace-write");
        assertThat(denied.reason()).contains("file writes are not allowed");
    }
}
