package org.jclaude.cli.parity;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Per-scenario workspace layout used by the mock-parity harness. Mirrors the Rust
 * {@code HarnessWorkspace} struct: a {@code workspace} directory passed as the CLI's
 * working directory, with sibling {@code home} and {@code config-home} directories
 * fed via {@code HOME} and {@code JCLAUDE_CONFIG_HOME} env vars. The root directory
 * is supplied by the caller (typically a JUnit {@code @TempDir}).
 */
public final class HarnessWorkspace {

    private final Path root;
    private final Path workspace;
    private final Path home;
    private final Path config_home;

    public HarnessWorkspace(Path root) {
        this.root = root;
        this.workspace = root.resolve("workspace");
        this.home = root.resolve("home");
        this.config_home = root.resolve("config-home");
    }

    public Path root() {
        return root;
    }

    public Path workspace() {
        return workspace;
    }

    public Path home() {
        return home;
    }

    public Path config_home() {
        return config_home;
    }

    public void create() throws IOException {
        Files.createDirectories(workspace);
        Files.createDirectories(home);
        Files.createDirectories(config_home);
    }

    /** Populate per-scenario fixtures. Mirrors the Rust harness's {@code prepare_*} helpers. */
    public void prepare(String scenario) throws IOException {
        switch (scenario) {
            case "read_file_roundtrip" -> Files.writeString(
                    workspace.resolve("fixture.txt"), "alpha parity line\n", UTF_8);
            case "grep_chunk_assembly", "multi_tool_turn_roundtrip" -> Files.writeString(
                    workspace.resolve("fixture.txt"), "alpha parity line\nbeta line\ngamma parity line\n", UTF_8);
            case "plugin_tool_roundtrip" -> prepare_plugin_fixture();
            default -> {
                /* noop */
            }
        }
    }

    private void prepare_plugin_fixture() throws IOException {
        Path plugin_root = workspace.resolve("external-plugins").resolve("parity-plugin");
        Path tools_dir = plugin_root.resolve("tools");
        Path manifest_dir = plugin_root.resolve(".claude-plugin");
        Files.createDirectories(tools_dir);
        Files.createDirectories(manifest_dir);

        Path script = tools_dir.resolve("echo-json.sh");
        String script_body = "#!/bin/sh\n"
                + "INPUT=$(cat)\n"
                + "printf '{\"plugin\":\"%s\",\"tool\":\"%s\",\"input\":%s}\\n'"
                + " \"$CLAWD_PLUGIN_ID\" \"$CLAWD_TOOL_NAME\" \"$INPUT\"\n";
        Files.writeString(script, script_body, UTF_8);
        try {
            Files.setPosixFilePermissions(
                    script,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem — leave defaults; harness only runs on POSIX hosts.
        }

        String manifest = "{\n"
                + "  \"name\": \"parity-plugin\",\n"
                + "  \"version\": \"1.0.0\",\n"
                + "  \"description\": \"mock parity plugin\",\n"
                + "  \"tools\": [\n"
                + "    {\n"
                + "      \"name\": \"plugin_echo\",\n"
                + "      \"description\": \"Echo JSON input\",\n"
                + "      \"inputSchema\": {\n"
                + "        \"type\": \"object\",\n"
                + "        \"properties\": {\n"
                + "          \"message\": { \"type\": \"string\" }\n"
                + "        },\n"
                + "        \"required\": [\"message\"],\n"
                + "        \"additionalProperties\": false\n"
                + "      },\n"
                + "      \"command\": \"./tools/echo-json.sh\",\n"
                + "      \"requiredPermission\": \"workspace-write\"\n"
                + "    }\n"
                + "  ]\n"
                + "}";
        Files.writeString(manifest_dir.resolve("plugin.json"), manifest, UTF_8);

        // The plugin loader looks under `externalDirectories` for plugin folders.
        Path settings = config_home.resolve("settings.json");
        String external_root = plugin_root.getParent().toString().replace("\\", "\\\\");
        String settings_body = "{\n"
                + "  \"enabledPlugins\": {\n"
                + "    \"parity-plugin@external\": true\n"
                + "  },\n"
                + "  \"plugins\": {\n"
                + "    \"externalDirectories\": [\""
                + external_root
                + "\"]\n"
                + "  }\n"
                + "}";
        Files.writeString(settings, settings_body, UTF_8);
    }
}
