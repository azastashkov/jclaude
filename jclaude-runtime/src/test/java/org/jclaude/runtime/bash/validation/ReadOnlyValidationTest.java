package org.jclaude.runtime.bash.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.jclaude.runtime.permissions.PermissionMode;
import org.junit.jupiter.api.Test;

class ReadOnlyValidationTest {

    @Test
    void blocks_rm_in_read_only() {
        ValidationResult result = ReadOnlyValidation.validate("rm -rf /tmp/x", PermissionMode.READ_ONLY);
        assertThat(result).isInstanceOf(ValidationResult.Block.class);
        assertThat(((ValidationResult.Block) result).reason()).contains("rm");
    }

    @Test
    void allows_rm_in_workspace_write() {
        assertThat(ReadOnlyValidation.validate("rm -rf /tmp/x", PermissionMode.WORKSPACE_WRITE))
                .isEqualTo(ValidationResult.allow());
    }

    @Test
    void blocks_write_redirections_in_read_only() {
        ValidationResult result = ReadOnlyValidation.validate("echo hello > file.txt", PermissionMode.READ_ONLY);
        assertThat(result).isInstanceOf(ValidationResult.Block.class);
        assertThat(((ValidationResult.Block) result).reason()).contains("redirection");
    }

    @Test
    void allows_read_commands_in_read_only() {
        assertThat(ReadOnlyValidation.validate("ls -la", PermissionMode.READ_ONLY))
                .isEqualTo(ValidationResult.allow());
        assertThat(ReadOnlyValidation.validate("cat /etc/hosts", PermissionMode.READ_ONLY))
                .isEqualTo(ValidationResult.allow());
        assertThat(ReadOnlyValidation.validate("grep -r pattern .", PermissionMode.READ_ONLY))
                .isEqualTo(ValidationResult.allow());
    }

    @Test
    void blocks_sudo_write_in_read_only() {
        ValidationResult result = ReadOnlyValidation.validate("sudo rm -rf /tmp/x", PermissionMode.READ_ONLY);
        assertThat(result).isInstanceOf(ValidationResult.Block.class);
        assertThat(((ValidationResult.Block) result).reason()).contains("rm");
    }

    @Test
    void blocks_git_push_in_read_only() {
        ValidationResult result = ReadOnlyValidation.validate("git push origin main", PermissionMode.READ_ONLY);
        assertThat(result).isInstanceOf(ValidationResult.Block.class);
        assertThat(((ValidationResult.Block) result).reason()).contains("push");
    }

    @Test
    void allows_git_status_in_read_only() {
        assertThat(ReadOnlyValidation.validate("git status", PermissionMode.READ_ONLY))
                .isEqualTo(ValidationResult.allow());
    }

    @Test
    void blocks_package_install_in_read_only() {
        ValidationResult result = ReadOnlyValidation.validate("npm install express", PermissionMode.READ_ONLY);
        assertThat(result).isInstanceOf(ValidationResult.Block.class);
        assertThat(((ValidationResult.Block) result).reason()).contains("npm");
    }
}
