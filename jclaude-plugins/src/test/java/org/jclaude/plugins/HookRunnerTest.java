package org.jclaude.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class HookRunnerTest {

    private static final AtomicLong COUNTER = new AtomicLong(0);

    private static Path temp_dir(String label) throws IOException {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"))
                .resolve("jclaude-hooks-" + label + "-" + System.nanoTime() + "-" + COUNTER.incrementAndGet());
        Files.createDirectories(dir);
        return dir;
    }

    private static void make_executable(Path path) throws IOException {
        try {
            Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(path));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException ignored) {
            path.toFile().setExecutable(true);
        }
    }

    private static void write_hook_plugin(Path root, String name, String pre, String post, String fail)
            throws IOException {
        Files.createDirectories(root.resolve(".claude-plugin"));
        Files.createDirectories(root.resolve("hooks"));

        Path pre_path = root.resolve("hooks/pre.sh");
        Files.writeString(pre_path, "#!/bin/sh\nprintf '%s\\n' '" + pre + "'\n");
        make_executable(pre_path);
        Path post_path = root.resolve("hooks/post.sh");
        Files.writeString(post_path, "#!/bin/sh\nprintf '%s\\n' '" + post + "'\n");
        make_executable(post_path);
        Path fail_path = root.resolve("hooks/failure.sh");
        Files.writeString(fail_path, "#!/bin/sh\nprintf '%s\\n' '" + fail + "'\n");
        make_executable(fail_path);
        Files.writeString(
                root.resolve(".claude-plugin/plugin.json"),
                "{\n  \"name\": \"" + name
                        + "\",\n  \"version\": \"1.0.0\",\n  \"description\": \"hook plugin\",\n  \"hooks\": {\n    \"PreToolUse\": [\"./hooks/pre.sh\"],\n    \"PostToolUse\": [\"./hooks/post.sh\"],\n    \"PostToolUseFailure\": [\"./hooks/failure.sh\"]\n  }\n}");
    }

    @Test
    void collects_and_runs_hooks_from_enabled_plugins() throws Exception {
        Path config_home = temp_dir("config");
        Path empty_bundled = temp_dir("config-empty-bundled");
        Path first_source_root = temp_dir("source-a");
        Path second_source_root = temp_dir("source-b");
        write_hook_plugin(first_source_root, "first", "plugin pre one", "plugin post one", "plugin failure one");
        write_hook_plugin(second_source_root, "second", "plugin pre two", "plugin post two", "plugin failure two");

        PluginManagerConfig config = new PluginManagerConfig(config_home).with_bundled_root(empty_bundled);
        PluginManager manager = new PluginManager(config);
        manager.install(first_source_root.toString());
        manager.install(second_source_root.toString());
        var registry = manager.plugin_registry();

        HookRunner runner = HookRunner.from_registry(registry);

        var pre = runner.run_pre_tool_use("Read", "{\"path\":\"README.md\"}");
        assertThat(pre.is_denied()).isFalse();
        assertThat(pre.is_failed()).isFalse();
        assertThat(pre.messages()).containsExactly("plugin pre one", "plugin pre two");

        var post = runner.run_post_tool_use("Read", "{\"path\":\"README.md\"}", "ok", false);
        assertThat(post.messages()).containsExactly("plugin post one", "plugin post two");

        var failure = runner.run_post_tool_use_failure("Read", "{\"path\":\"README.md\"}", "tool failed");
        assertThat(failure.messages()).containsExactly("plugin failure one", "plugin failure two");
    }

    @Test
    void pre_tool_use_denies_when_plugin_hook_exits_two() {
        HookRunner runner =
                new HookRunner(new PluginHooks(List.of("printf 'blocked by plugin'; exit 2"), List.of(), List.of()));
        HookRunResult result = runner.run_pre_tool_use("Bash", "{\"command\":\"pwd\"}");
        assertThat(result.is_denied()).isTrue();
        assertThat(result.messages()).containsExactly("blocked by plugin");
    }

    @Test
    void propagates_plugin_hook_failures() {
        HookRunner runner = new HookRunner(new PluginHooks(
                List.of("printf 'broken plugin hook'; exit 1", "printf 'later plugin hook'"), List.of(), List.of()));
        HookRunResult result = runner.run_pre_tool_use("Bash", "{\"command\":\"pwd\"}");
        assertThat(result.is_failed()).isTrue();
        assertThat(result.messages()).anyMatch(m -> m.contains("broken plugin hook"));
        assertThat(result.messages()).noneMatch(m -> m.equals("later plugin hook"));
    }

    @Test
    void generated_hook_scripts_are_executable() throws Exception {
        Path root = temp_dir("exec-guard");
        write_hook_plugin(root, "exec-check", "pre", "post", "fail");
        for (String script : new String[] {"pre.sh", "post.sh", "failure.sh"}) {
            Path path = root.resolve("hooks/" + script);
            assertThat(Files.isExecutable(path)).isTrue();
        }
    }
}
