package org.jclaude.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PluginManifestLoaderTest {

    private static final AtomicLong COUNTER = new AtomicLong(0);

    private static Path temp_dir(String label) throws IOException {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"))
                .resolve("jclaude-plugins-" + label + "-" + System.nanoTime() + "-" + COUNTER.incrementAndGet());
        Files.createDirectories(dir);
        return dir;
    }

    private static void write_file(Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }

    private static void write_loader_plugin(Path root) throws IOException {
        write_file(root.resolve("hooks/pre.sh"), "#!/bin/sh\nprintf 'pre'\n");
        write_file(root.resolve("tools/echo-tool.sh"), "#!/bin/sh\ncat\n");
        write_file(root.resolve("commands/sync.sh"), "#!/bin/sh\nprintf 'sync'\n");
        write_file(
                root.resolve("plugin.json"),
                """
                {
                  "name": "loader-demo",
                  "version": "1.2.3",
                  "description": "Manifest loader test plugin",
                  "permissions": ["read", "write"],
                  "hooks": {"PreToolUse": ["./hooks/pre.sh"]},
                  "tools": [
                    {
                      "name": "echo_tool",
                      "description": "Echoes JSON input",
                      "inputSchema": {"type":"object"},
                      "command": "./tools/echo-tool.sh",
                      "requiredPermission": "workspace-write"
                    }
                  ],
                  "commands": [
                    {"name": "sync", "description": "Sync command", "command": "./commands/sync.sh"}
                  ]
                }
                """);
    }

    private static void write_external_plugin(Path root, String name, String version) throws IOException {
        write_file(root.resolve("hooks/pre.sh"), "#!/bin/sh\nprintf 'pre'\n");
        write_file(root.resolve("hooks/post.sh"), "#!/bin/sh\nprintf 'post'\n");
        write_file(
                root.resolve(".claude-plugin/plugin.json"),
                "{\n  \"name\": \"" + name + "\",\n  \"version\": \"" + version
                        + "\",\n  \"description\": \"test plugin\",\n  \"hooks\": {\n    \"PreToolUse\": [\"./hooks/pre.sh\"],\n    \"PostToolUse\": [\"./hooks/post.sh\"]\n  }\n}");
    }

    @Test
    void load_plugin_from_directory_validates_required_fields() throws Exception {
        Path root = temp_dir("manifest-required");
        write_file(root.resolve("plugin.json"), "{\"name\":\"\",\"version\":\"1.0.0\",\"description\":\"desc\"}");

        assertThatThrownBy(() -> PluginManager.load_plugin_from_directory(root))
                .isInstanceOf(PluginError.class)
                .hasMessageContaining("name cannot be empty");
    }

    @Test
    void load_plugin_from_directory_reads_root_manifest_and_validates_entries() throws Exception {
        Path root = temp_dir("manifest-root");
        write_loader_plugin(root);

        PluginManifest manifest = PluginManager.load_plugin_from_directory(root);
        assertThat(manifest.name()).isEqualTo("loader-demo");
        assertThat(manifest.version()).isEqualTo("1.2.3");
        assertThat(manifest.permissions()).extracting(PluginPermission::as_str).containsExactly("read", "write");
        assertThat(manifest.hooks().pre_tool_use()).containsExactly("./hooks/pre.sh");
        assertThat(manifest.tools()).hasSize(1);
        assertThat(manifest.tools().get(0).name()).isEqualTo("echo_tool");
        assertThat(manifest.tools().get(0).required_permission()).isEqualTo(PluginToolPermission.WORKSPACE_WRITE);
        assertThat(manifest.commands()).hasSize(1);
        assertThat(manifest.commands().get(0).name()).isEqualTo("sync");
    }

    @Test
    void load_plugin_from_directory_supports_packaged_manifest_path() throws Exception {
        Path root = temp_dir("manifest-packaged");
        write_external_plugin(root, "packaged-demo", "1.0.0");

        PluginManifest manifest = PluginManager.load_plugin_from_directory(root);
        assertThat(manifest.name()).isEqualTo("packaged-demo");
        assertThat(manifest.tools()).isEmpty();
        assertThat(manifest.commands()).isEmpty();
    }

    @Test
    void load_plugin_from_directory_defaults_optional_fields() throws Exception {
        Path root = temp_dir("manifest-defaults");
        write_file(
                root.resolve("plugin.json"),
                """
                {"name":"minimal","version":"0.1.0","description":"Minimal manifest"}
                """);

        PluginManifest manifest = PluginManager.load_plugin_from_directory(root);
        assertThat(manifest.permissions()).isEmpty();
        assertThat(manifest.hooks().is_empty()).isTrue();
        assertThat(manifest.tools()).isEmpty();
        assertThat(manifest.commands()).isEmpty();
    }

    @Test
    void load_plugin_from_directory_rejects_duplicate_permissions_and_commands() throws Exception {
        Path root = temp_dir("manifest-duplicates");
        write_file(root.resolve("commands/sync.sh"), "#!/bin/sh\nprintf 'sync'\n");
        write_file(
                root.resolve("plugin.json"),
                """
                {
                  "name": "duplicate-manifest",
                  "version": "1.0.0",
                  "description": "Duplicate validation",
                  "permissions": ["read", "read"],
                  "commands": [
                    {"name": "sync", "description": "Sync one", "command": "./commands/sync.sh"},
                    {"name": "sync", "description": "Sync two", "command": "./commands/sync.sh"}
                  ]
                }
                """);

        assertThatThrownBy(() -> PluginManager.load_plugin_from_directory(root))
                .isInstanceOf(PluginError.class)
                .satisfies(error -> {
                    PluginError pe = (PluginError) error;
                    assertThat(pe.kind()).isEqualTo(PluginError.Kind.MANIFEST_VALIDATION);
                    assertThat(pe.manifest_errors())
                            .anyMatch(e -> e instanceof PluginManifestValidationError.DuplicatePermission dp
                                    && dp.permission().equals("read"));
                    assertThat(pe.manifest_errors())
                            .anyMatch(e -> e instanceof PluginManifestValidationError.DuplicateEntry de
                                    && de.kind().equals("command")
                                    && de.name().equals("sync"));
                });
    }

    @Test
    void load_plugin_from_directory_rejects_claude_code_manifest_contracts_with_guidance() throws Exception {
        Path root = temp_dir("manifest-claude-code-contract");
        write_file(
                root.resolve("plugin.json"),
                """
                {
                  "name": "oh-my-claudecode",
                  "version": "4.10.2",
                  "description": "Claude Code plugin manifest",
                  "hooks": {"SessionStart": ["scripts/session-start.mjs"]},
                  "agents": ["agents/*.md"],
                  "commands": ["commands/**/*.md"],
                  "skills": "./skills/",
                  "mcpServers": "./.mcp.json"
                }
                """);

        assertThatThrownBy(() -> PluginManager.load_plugin_from_directory(root))
                .isInstanceOf(PluginError.class)
                .satisfies(error -> {
                    String rendered = ((PluginError) error).getMessage();
                    assertThat(rendered).contains("field `skills` uses the Claude Code plugin contract");
                    assertThat(rendered).contains("field `mcpServers` uses the Claude Code plugin contract");
                    assertThat(rendered).contains("field `agents` uses the Claude Code plugin contract");
                    assertThat(rendered).contains("field `commands` uses Claude Code-style directory globs");
                    assertThat(rendered).contains("hook `SessionStart` uses the Claude Code lifecycle contract");
                });
    }

    @Test
    void load_plugin_from_directory_rejects_missing_tool_or_command_paths() throws Exception {
        Path root = temp_dir("manifest-paths");
        write_file(
                root.resolve("plugin.json"),
                """
                {
                  "name": "missing-paths",
                  "version": "1.0.0",
                  "description": "Missing path validation",
                  "tools": [
                    {
                      "name": "tool_one",
                      "description": "Missing tool script",
                      "inputSchema": {"type":"object"},
                      "command": "./tools/missing.sh"
                    }
                  ]
                }
                """);
        assertThatThrownBy(() -> PluginManager.load_plugin_from_directory(root))
                .isInstanceOf(PluginError.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void load_plugin_from_directory_rejects_missing_lifecycle_paths() throws Exception {
        Path root = temp_dir("manifest-lifecycle-paths");
        write_file(
                root.resolve("plugin.json"),
                """
                {
                  "name": "missing-lifecycle-paths",
                  "version": "1.0.0",
                  "description": "Missing lifecycle path validation",
                  "lifecycle": {
                    "Init": ["./lifecycle/init.sh"],
                    "Shutdown": ["./lifecycle/shutdown.sh"]
                  }
                }
                """);
        assertThatThrownBy(() -> PluginManager.load_plugin_from_directory(root))
                .isInstanceOf(PluginError.class)
                .satisfies(error -> {
                    PluginError pe = (PluginError) error;
                    assertThat(pe.manifest_errors())
                            .anyMatch(e -> e instanceof PluginManifestValidationError.MissingPath mp
                                    && mp.kind().equals("lifecycle command")
                                    && mp.path().toString().endsWith("lifecycle/init.sh"));
                    assertThat(pe.manifest_errors())
                            .anyMatch(e -> e instanceof PluginManifestValidationError.MissingPath mp
                                    && mp.kind().equals("lifecycle command")
                                    && mp.path().toString().endsWith("lifecycle/shutdown.sh"));
                });
    }

    @Test
    void load_plugin_from_directory_rejects_directory_command_paths() throws Exception {
        Path root = temp_dir("manifest-directory-paths");
        Files.createDirectories(root.resolve("hooks/pre-dir"));
        Files.createDirectories(root.resolve("tools/tool-dir"));
        Files.createDirectories(root.resolve("commands/sync-dir"));
        Files.createDirectories(root.resolve("lifecycle/init-dir"));
        write_file(
                root.resolve("plugin.json"),
                """
                {
                  "name": "directory-paths",
                  "version": "1.0.0",
                  "description": "directory path plugin",
                  "hooks": {"PreToolUse": ["./hooks/pre-dir"]},
                  "lifecycle": {"Init": ["./lifecycle/init-dir"]},
                  "tools": [
                    {"name": "dir_tool", "description": "Directory tool", "inputSchema": {"type":"object"}, "command": "./tools/tool-dir"}
                  ],
                  "commands": [
                    {"name": "sync", "description": "Directory command", "command": "./commands/sync-dir"}
                  ]
                }
                """);
        assertThatThrownBy(() -> PluginManager.load_plugin_from_directory(root))
                .isInstanceOf(PluginError.class)
                .satisfies(error -> {
                    PluginError pe = (PluginError) error;
                    assertThat(pe.manifest_errors())
                            .anyMatch(e -> e instanceof PluginManifestValidationError.PathIsDirectory pid
                                    && pid.kind().equals("hook"))
                            .anyMatch(e -> e instanceof PluginManifestValidationError.PathIsDirectory pid
                                    && pid.kind().equals("lifecycle command"))
                            .anyMatch(e -> e instanceof PluginManifestValidationError.PathIsDirectory pid
                                    && pid.kind().equals("tool"))
                            .anyMatch(e -> e instanceof PluginManifestValidationError.PathIsDirectory pid
                                    && pid.kind().equals("command"));
                });
    }

    @Test
    void load_plugin_from_directory_rejects_invalid_permissions() throws Exception {
        Path root = temp_dir("manifest-invalid-permissions");
        write_file(
                root.resolve("plugin.json"),
                """
                {"name":"invalid-permissions","version":"1.0.0","description":"Invalid permission validation","permissions":["admin"]}
                """);
        assertThatThrownBy(() -> PluginManager.load_plugin_from_directory(root))
                .isInstanceOf(PluginError.class)
                .satisfies(error -> {
                    PluginError pe = (PluginError) error;
                    assertThat(pe.manifest_errors())
                            .anyMatch(e -> e instanceof PluginManifestValidationError.InvalidPermission ip
                                    && ip.permission().equals("admin"));
                });
    }

    @Test
    void load_plugin_from_directory_rejects_invalid_tool_required_permission() throws Exception {
        Path root = temp_dir("manifest-invalid-tool-permission");
        write_file(root.resolve("tools/echo.sh"), "#!/bin/sh\ncat\n");
        write_file(
                root.resolve("plugin.json"),
                """
                {
                  "name": "invalid-tool-permission",
                  "version": "1.0.0",
                  "description": "Invalid tool permission validation",
                  "tools": [
                    {"name":"echo_tool","description":"Echo tool","inputSchema":{"type":"object"},"command":"./tools/echo.sh","requiredPermission":"admin"}
                  ]
                }
                """);
        assertThatThrownBy(() -> PluginManager.load_plugin_from_directory(root))
                .isInstanceOf(PluginError.class)
                .satisfies(error -> {
                    PluginError pe = (PluginError) error;
                    assertThat(pe.manifest_errors())
                            .anyMatch(e -> e instanceof PluginManifestValidationError.InvalidToolRequiredPermission ip
                                    && ip.tool_name().equals("echo_tool")
                                    && ip.permission().equals("admin"));
                });
    }

    @Test
    void load_plugin_from_directory_accumulates_multiple_validation_errors() throws Exception {
        Path root = temp_dir("manifest-multi-error");
        write_file(
                root.resolve("plugin.json"),
                """
                {
                  "name": "",
                  "version": "1.0.0",
                  "description": "",
                  "permissions": ["admin"],
                  "commands": [
                    {"name": "", "description": "", "command": "./commands/missing.sh"}
                  ]
                }
                """);
        assertThatThrownBy(() -> PluginManager.load_plugin_from_directory(root))
                .isInstanceOf(PluginError.class)
                .satisfies(error -> {
                    PluginError pe = (PluginError) error;
                    assertThat(pe.manifest_errors().size()).isGreaterThanOrEqualTo(4);
                    assertThat(pe.manifest_errors())
                            .anyMatch(e -> e instanceof PluginManifestValidationError.EmptyField ef
                                    && ef.field().equals("name"))
                            .anyMatch(e -> e instanceof PluginManifestValidationError.EmptyField ef
                                    && ef.field().equals("description"))
                            .anyMatch(e -> e instanceof PluginManifestValidationError.InvalidPermission ip
                                    && ip.permission().equals("admin"));
                });
    }
}
