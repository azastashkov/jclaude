package org.jclaude.runtime.bash.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class BashSecurityTest {

    @Test
    void warns_destructive_first() {
        Path workspace = Paths.get("/workspace");
        ValidationResult result = BashSecurity.validate("rm -rf /", workspace);
        assertThat(result).isInstanceOf(ValidationResult.Warn.class);
    }

    @Test
    void warns_path_traversal_when_no_destructive() {
        Path workspace = Paths.get("/workspace");
        ValidationResult result = BashSecurity.validate("cat ../../../etc/passwd", workspace);
        assertThat(result).isInstanceOf(ValidationResult.Warn.class);
        assertThat(((ValidationResult.Warn) result).message()).contains("traversal");
    }

    @Test
    void allows_safe_command() {
        Path workspace = Paths.get("/workspace");
        assertThat(BashSecurity.validate("ls -la", workspace)).isEqualTo(ValidationResult.allow());
    }
}
