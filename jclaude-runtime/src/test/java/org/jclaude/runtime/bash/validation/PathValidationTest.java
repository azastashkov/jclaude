package org.jclaude.runtime.bash.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class PathValidationTest {

    @Test
    void warns_directory_traversal() {
        Path workspace = Paths.get("/workspace/project");
        ValidationResult result = PathValidation.validate("cat ../../../etc/passwd", workspace);
        assertThat(result).isInstanceOf(ValidationResult.Warn.class);
        assertThat(((ValidationResult.Warn) result).message()).contains("traversal");
    }

    @Test
    void warns_home_directory_reference() {
        Path workspace = Paths.get("/workspace/project");
        ValidationResult result = PathValidation.validate("cat ~/.ssh/id_rsa", workspace);
        assertThat(result).isInstanceOf(ValidationResult.Warn.class);
        assertThat(((ValidationResult.Warn) result).message()).contains("home directory");
    }
}
