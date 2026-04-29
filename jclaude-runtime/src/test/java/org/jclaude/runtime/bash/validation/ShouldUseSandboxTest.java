package org.jclaude.runtime.bash.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.jclaude.runtime.permissions.PermissionMode;
import org.junit.jupiter.api.Test;

class ShouldUseSandboxTest {

    @Test
    void read_only_command_skips_sandbox() {
        assertThat(ShouldUseSandbox.decide("ls -la", PermissionMode.WORKSPACE_WRITE))
                .isFalse();
    }

    @Test
    void write_command_uses_sandbox() {
        assertThat(ShouldUseSandbox.decide("cp a b", PermissionMode.WORKSPACE_WRITE))
                .isTrue();
    }

    @Test
    void destructive_command_uses_sandbox() {
        assertThat(ShouldUseSandbox.decide("rm -rf /tmp/x", PermissionMode.WORKSPACE_WRITE))
                .isTrue();
    }

    @Test
    void danger_full_access_disables_sandbox() {
        assertThat(ShouldUseSandbox.decide("rm -rf /tmp/x", PermissionMode.DANGER_FULL_ACCESS))
                .isFalse();
    }

    @Test
    void allow_mode_disables_sandbox() {
        assertThat(ShouldUseSandbox.decide("rm -rf /tmp/x", PermissionMode.ALLOW))
                .isFalse();
    }
}
