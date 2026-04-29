package org.jclaude.runtime.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class PathUtilsTest {

    @Test
    void expand_braces_no_braces() {
        assertThat(PathUtils.expand_braces("*.rs")).containsExactly("*.rs");
    }

    @Test
    void expand_braces_single_group() {
        var result = new java.util.ArrayList<>(PathUtils.expand_braces("Assets/**/*.{cs,uxml,uss}"));
        java.util.Collections.sort(result);
        assertThat(result).containsExactly("Assets/**/*.cs", "Assets/**/*.uss", "Assets/**/*.uxml");
    }

    @Test
    void expand_braces_nested() {
        var result = new java.util.ArrayList<>(PathUtils.expand_braces("src/{a,b}.{rs,toml}"));
        java.util.Collections.sort(result);
        assertThat(result).containsExactly("src/a.rs", "src/a.toml", "src/b.rs", "src/b.toml");
    }

    @Test
    void expand_braces_unmatched() {
        assertThat(PathUtils.expand_braces("foo.{bar")).containsExactly("foo.{bar");
    }

    @Test
    void detects_binary_file_with_nul_bytes(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("binary.bin");
        Files.write(path, new byte[] {0x00, 0x01, 0x02, 0x03, 'h', 'i'});
        assertThat(PathUtils.is_binary_file(path)).isTrue();
    }

    @Test
    void detects_text_file_without_nul_bytes(@TempDir Path workspace) throws IOException {
        Path path = workspace.resolve("text.txt");
        Files.writeString(path, "regular ascii content");
        assertThat(PathUtils.is_binary_file(path)).isFalse();
    }

    @Test
    void normalize_path_resolves_relative_to_workspace(@TempDir Path workspace) throws IOException {
        Path inside = workspace.resolve("inside.txt");
        Files.writeString(inside, "hi");
        Path resolved = PathUtils.normalize_path("inside.txt", workspace);
        assertThat(resolved).isEqualTo(inside.toRealPath());
    }

    @Test
    void normalize_path_rejects_missing_files(@TempDir Path workspace) {
        assertThatThrownBy(() -> PathUtils.normalize_path("missing.txt", workspace))
                .isInstanceOf(FileOpsException.class)
                .matches(error -> ((FileOpsException) error).kind() == FileOpsException.Kind.NOT_FOUND);
    }

    @Test
    void validate_workspace_boundary_accepts_inside(@TempDir Path workspace) throws IOException {
        Path inside = workspace.resolve("inside.txt");
        Files.writeString(inside, "data");
        PathUtils.validate_workspace_boundary(inside.toRealPath(), workspace);
    }

    @Test
    void validate_workspace_boundary_rejects_outside(@TempDir Path workspace, @TempDir Path other) throws IOException {
        Path outside = other.resolve("outside.txt");
        Files.writeString(outside, "data");
        assertThatThrownBy(() -> PathUtils.validate_workspace_boundary(outside.toRealPath(), workspace))
                .isInstanceOf(FileOpsException.class)
                .matches(error -> ((FileOpsException) error).kind() == FileOpsException.Kind.PERMISSION_DENIED)
                .hasMessageContaining("escapes workspace");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void detects_symlink_escape(@TempDir Path workspace, @TempDir Path other) throws IOException {
        Path target = other.resolve("target.txt");
        Files.writeString(target, "outside");

        Path link = workspace.resolve("escape-link.txt");
        Files.createSymbolicLink(link, target);
        assertThat(PathUtils.is_symlink_escape(link, workspace)).isTrue();

        Path normal = workspace.resolve("normal.txt");
        Files.writeString(normal, "inside");
        assertThat(PathUtils.is_symlink_escape(normal, workspace)).isFalse();
    }
}
