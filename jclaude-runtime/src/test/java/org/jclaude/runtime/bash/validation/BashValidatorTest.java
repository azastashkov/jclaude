package org.jclaude.runtime.bash.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.jclaude.runtime.permissions.PermissionMode;
import org.junit.jupiter.api.Test;

class BashValidatorTest {

    @Test
    void pipeline_blocks_write_in_read_only() {
        Path workspace = Paths.get("/workspace");
        ValidationResult result = BashValidator.validate("rm -rf /tmp/x", PermissionMode.READ_ONLY, workspace);
        assertThat(result).isInstanceOf(ValidationResult.Block.class);
    }

    @Test
    void pipeline_warns_destructive_in_write_mode() {
        Path workspace = Paths.get("/workspace");
        ValidationResult result = BashValidator.validate("rm -rf /", PermissionMode.WORKSPACE_WRITE, workspace);
        assertThat(result).isInstanceOf(ValidationResult.Warn.class);
    }

    @Test
    void pipeline_allows_safe_read_in_read_only() {
        Path workspace = Paths.get("/workspace");
        assertThat(BashValidator.validate("ls -la", PermissionMode.READ_ONLY, workspace))
                .isEqualTo(ValidationResult.allow());
    }

    @Test
    void extracts_command_from_env_prefix() {
        assertThat(CommandHelpers.extract_first_command("FOO=bar ls -la")).isEqualTo("ls");
        assertThat(CommandHelpers.extract_first_command("A=1 B=2 echo hello")).isEqualTo("echo");
    }

    @Test
    void extracts_plain_command() {
        assertThat(CommandHelpers.extract_first_command("grep -r pattern .")).isEqualTo("grep");
    }
}
