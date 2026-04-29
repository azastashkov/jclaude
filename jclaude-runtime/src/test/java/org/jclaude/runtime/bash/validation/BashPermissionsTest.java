package org.jclaude.runtime.bash.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.jclaude.runtime.permissions.PermissionMode;
import org.junit.jupiter.api.Test;

class BashPermissionsTest {

    @Test
    void read_only_intent_requires_read_only_mode() {
        assertThat(BashPermissions.required_mode(CommandIntent.READ_ONLY)).isEqualTo(PermissionMode.READ_ONLY);
    }

    @Test
    void write_intent_requires_workspace_write() {
        assertThat(BashPermissions.required_mode(CommandIntent.WRITE)).isEqualTo(PermissionMode.WORKSPACE_WRITE);
    }

    @Test
    void destructive_intent_requires_danger_full_access() {
        assertThat(BashPermissions.required_mode(CommandIntent.DESTRUCTIVE))
                .isEqualTo(PermissionMode.DANGER_FULL_ACCESS);
    }

    @Test
    void allow_mode_permits_any_intent() {
        assertThat(BashPermissions.validate(CommandIntent.DESTRUCTIVE, PermissionMode.ALLOW))
                .isEqualTo(ValidationResult.allow());
    }

    @Test
    void read_only_mode_blocks_write_intent() {
        ValidationResult result = BashPermissions.validate(CommandIntent.WRITE, PermissionMode.READ_ONLY);
        assertThat(result).isInstanceOf(ValidationResult.Block.class);
    }

    @Test
    void validate_command_classifies_and_checks() {
        assertThat(BashPermissions.validate_command("ls -la", PermissionMode.READ_ONLY))
                .isEqualTo(ValidationResult.allow());
        assertThat(BashPermissions.validate_command("rm -rf /tmp/x", PermissionMode.WORKSPACE_WRITE))
                .isInstanceOf(ValidationResult.Block.class);
    }
}
