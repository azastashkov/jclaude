package org.jclaude.runtime.bash.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DestructiveCommandWarningTest {

    @Test
    void warns_rm_rf_root() {
        ValidationResult result = DestructiveCommandWarning.check("rm -rf /");
        assertThat(result).isInstanceOf(ValidationResult.Warn.class);
        assertThat(((ValidationResult.Warn) result).message()).contains("root");
    }

    @Test
    void warns_rm_rf_home() {
        ValidationResult result = DestructiveCommandWarning.check("rm -rf ~");
        assertThat(result).isInstanceOf(ValidationResult.Warn.class);
        assertThat(((ValidationResult.Warn) result).message()).contains("home");
    }

    @Test
    void warns_shred() {
        ValidationResult result = DestructiveCommandWarning.check("shred /dev/sda");
        assertThat(result).isInstanceOf(ValidationResult.Warn.class);
        assertThat(((ValidationResult.Warn) result).message()).contains("destructive");
    }

    @Test
    void warns_fork_bomb() {
        ValidationResult result = DestructiveCommandWarning.check(":(){ :|:& };:");
        assertThat(result).isInstanceOf(ValidationResult.Warn.class);
        assertThat(((ValidationResult.Warn) result).message()).contains("Fork bomb");
    }

    @Test
    void allows_safe_commands() {
        assertThat(DestructiveCommandWarning.check("ls -la")).isEqualTo(ValidationResult.allow());
        assertThat(DestructiveCommandWarning.check("echo hello")).isEqualTo(ValidationResult.allow());
    }
}
