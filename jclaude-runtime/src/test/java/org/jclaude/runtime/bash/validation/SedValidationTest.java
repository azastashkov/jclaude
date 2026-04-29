package org.jclaude.runtime.bash.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.jclaude.runtime.permissions.PermissionMode;
import org.junit.jupiter.api.Test;

class SedValidationTest {

    @Test
    void blocks_sed_inplace_in_read_only() {
        ValidationResult result = SedValidation.validate("sed -i 's/old/new/' file.txt", PermissionMode.READ_ONLY);
        assertThat(result).isInstanceOf(ValidationResult.Block.class);
        assertThat(((ValidationResult.Block) result).reason()).contains("sed -i");
    }

    @Test
    void allows_sed_stdout_in_read_only() {
        assertThat(SedValidation.validate("sed 's/old/new/' file.txt", PermissionMode.READ_ONLY))
                .isEqualTo(ValidationResult.allow());
    }
}
