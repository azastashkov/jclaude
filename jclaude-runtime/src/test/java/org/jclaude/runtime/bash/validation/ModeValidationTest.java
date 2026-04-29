package org.jclaude.runtime.bash.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.jclaude.runtime.permissions.PermissionMode;
import org.junit.jupiter.api.Test;

class ModeValidationTest {

    @Test
    void workspace_write_warns_system_paths() {
        ValidationResult result = ModeValidation.validate("cp file.txt /etc/config", PermissionMode.WORKSPACE_WRITE);
        assertThat(result).isInstanceOf(ValidationResult.Warn.class);
        assertThat(((ValidationResult.Warn) result).message()).contains("outside the workspace");
    }

    @Test
    void workspace_write_allows_local_writes() {
        assertThat(ModeValidation.validate("cp file.txt ./backup/", PermissionMode.WORKSPACE_WRITE))
                .isEqualTo(ValidationResult.allow());
    }
}
