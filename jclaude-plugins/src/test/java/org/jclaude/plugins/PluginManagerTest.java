package org.jclaude.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PluginManagerTest {

    private static final AtomicLong COUNTER = new AtomicLong(0);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path temp_dir(String label) throws IOException {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"))
                .resolve("jclaude-pm-" + label + "-" + System.nanoTime() + "-" + COUNTER.incrementAndGet());
        Files.createDirectories(dir);
        return dir;
    }

    private static void write_file(Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
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

    private static void write_external_plugin(Path root, String name, String version) throws IOException {
        write_file(root.resolve("hooks/pre.sh"), "#!/bin/sh\nprintf 'pre'\n");
        make_executable(root.resolve("hooks/pre.sh"));
        write_file(root.resolve("hooks/post.sh"), "#!/bin/sh\nprintf 'post'\n");
        make_executable(root.resolve("hooks/post.sh"));
        write_file(
                root.resolve(".claude-plugin/plugin.json"),
                "{\n  \"name\": \"" + name + "\",\n  \"version\": \"" + version
                        + "\",\n  \"description\": \"test plugin\",\n  \"hooks\": {\n    \"PreToolUse\": [\"./hooks/pre.sh\"],\n    \"PostToolUse\": [\"./hooks/post.sh\"]\n  }\n}");
    }

    private static void write_broken_plugin(Path root, String name) throws IOException {
        write_file(
                root.resolve(".claude-plugin/plugin.json"),
                "{\n  \"name\": \"" + name
                        + "\",\n  \"version\": \"1.0.0\",\n  \"description\": \"broken plugin\",\n  \"hooks\": {\n    \"PreToolUse\": [\"./hooks/missing.sh\"]\n  }\n}");
    }

    private static void write_broken_failure_hook_plugin(Path root, String name) throws IOException {
        write_file(
                root.resolve(".claude-plugin/plugin.json"),
                "{\n  \"name\": \"" + name
                        + "\",\n  \"version\": \"1.0.0\",\n  \"description\": \"broken plugin\",\n  \"hooks\": {\n    \"PostToolUseFailure\": [\"./hooks/missing-failure.sh\"]\n  }\n}");
    }

    private static void write_bundled_plugin(Path root, String name, String version, boolean default_enabled)
            throws IOException {
        write_file(
                root.resolve(".claude-plugin/plugin.json"),
                "{\n  \"name\": \"" + name + "\",\n  \"version\": \"" + version
                        + "\",\n  \"description\": \"bundled plugin\",\n  \"defaultEnabled\": "
                        + (default_enabled ? "true" : "false") + "\n}");
    }

    private static Path write_lifecycle_plugin(Path root, String name, String version) throws IOException {
        Path log_path = root.resolve("lifecycle.log");
        write_file(root.resolve("lifecycle/init.sh"), "#!/bin/sh\nprintf 'init\\n' >> lifecycle.log\n");
        make_executable(root.resolve("lifecycle/init.sh"));
        write_file(root.resolve("lifecycle/shutdown.sh"), "#!/bin/sh\nprintf 'shutdown\\n' >> lifecycle.log\n");
        make_executable(root.resolve("lifecycle/shutdown.sh"));
        write_file(
                root.resolve(".claude-plugin/plugin.json"),
                "{\n  \"name\": \"" + name + "\",\n  \"version\": \"" + version
                        + "\",\n  \"description\": \"lifecycle plugin\",\n  \"lifecycle\": {\n    \"Init\": [\"./lifecycle/init.sh\"],\n    \"Shutdown\": [\"./lifecycle/shutdown.sh\"]\n  }\n}");
        return log_path;
    }

    private static void write_tool_plugin(Path root, String name, String version) throws IOException {
        Path script_path = root.resolve("tools/echo-json.sh");
        write_file(
                script_path,
                "#!/bin/sh\nINPUT=$(cat)\nprintf '{\"plugin\":\"%s\",\"tool\":\"%s\",\"input\":%s}\\n' \"$CLAWD_PLUGIN_ID\" \"$CLAWD_TOOL_NAME\" \"$INPUT\"\n");
        make_executable(script_path);
        write_file(
                root.resolve(".claude-plugin/plugin.json"),
                "{\n  \"name\": \"" + name + "\",\n  \"version\": \"" + version
                        + "\",\n  \"description\": \"tool plugin\",\n  \"tools\": [\n    {\n      \"name\": \"plugin_echo\",\n      \"description\": \"Echo JSON input\",\n      \"inputSchema\": {\"type\": \"object\", \"properties\": {\"message\": {\"type\": \"string\"}}, \"required\": [\"message\"], \"additionalProperties\": false},\n      \"command\": \"./tools/echo-json.sh\",\n      \"requiredPermission\": \"workspace-write\"\n    }\n  ]\n}");
    }

    private PluginManagerConfig empty_config(Path config_home) throws IOException {
        Path bundled = temp_dir("empty-bundled");
        return new PluginManagerConfig(config_home).with_bundled_root(bundled);
    }

    @Test
    void discovers_builtin_and_bundled_plugins() throws Exception {
        PluginManager manager = new PluginManager(new PluginManagerConfig(temp_dir("discover")));
        var plugins = manager.list_plugins();
        assertThat(plugins).anyMatch(p -> p.metadata().kind() == PluginKind.BUILTIN);
        assertThat(plugins).anyMatch(p -> p.metadata().kind() == PluginKind.BUNDLED);
    }

    @Test
    void installs_enables_updates_and_uninstalls_external_plugins() throws Exception {
        Path config_home = temp_dir("home");
        Path source_root = temp_dir("source");
        write_external_plugin(source_root, "demo", "1.0.0");

        PluginManager manager = new PluginManager(empty_config(config_home));
        InstallOutcome install = manager.install(source_root.toString());
        assertThat(install.plugin_id()).isEqualTo("demo@external");

        assertThat(manager.list_plugins()).anyMatch(p -> p.metadata().id().equals("demo@external") && p.enabled());

        var hooks = manager.aggregated_hooks();
        assertThat(hooks.pre_tool_use()).hasSize(1);
        assertThat(hooks.pre_tool_use().get(0)).contains("pre.sh");

        manager.disable("demo@external");
        assertThat(manager.aggregated_hooks().is_empty()).isTrue();
        manager.enable("demo@external");

        write_external_plugin(source_root, "demo", "2.0.0");
        UpdateOutcome update = manager.update("demo@external");
        assertThat(update.old_version()).isEqualTo("1.0.0");
        assertThat(update.new_version()).isEqualTo("2.0.0");

        manager.uninstall("demo@external");
        assertThat(manager.list_plugins()).noneMatch(p -> p.metadata().id().equals("demo@external"));
    }

    @Test
    void auto_installs_bundled_plugins_into_the_registry() throws Exception {
        Path config_home = temp_dir("bundled-home");
        Path bundled_root = temp_dir("bundled-root");
        write_bundled_plugin(bundled_root.resolve("starter"), "starter", "0.1.0", false);

        PluginManagerConfig config = new PluginManagerConfig(config_home).with_bundled_root(bundled_root);
        PluginManager manager = new PluginManager(config);

        var installed = manager.list_installed_plugins();
        assertThat(installed)
                .anyMatch(p -> p.metadata().id().equals("starter@bundled")
                        && p.metadata().kind() == PluginKind.BUNDLED);

        var registry = manager.load_registry();
        var record = registry.get("starter@bundled");
        assertThat(record).isNotNull();
        assertThat(record.kind()).isEqualTo(PluginKind.BUNDLED);
        assertThat(Files.exists(record.install_path())).isTrue();
    }

    @Test
    void default_bundled_root_loads_repo_bundles_as_installed_plugins() throws Exception {
        Path config_home = temp_dir("default-bundled-home");
        PluginManager manager = new PluginManager(new PluginManagerConfig(config_home));

        var installed = manager.list_installed_plugins();
        assertThat(installed).anyMatch(p -> p.metadata().id().equals("example-bundled@bundled"));
        assertThat(installed).anyMatch(p -> p.metadata().id().equals("sample-hooks@bundled"));
    }

    @Test
    void bundled_sync_prunes_removed_bundled_registry_entries() throws Exception {
        Path config_home = temp_dir("bundled-prune-home");
        Path bundled_root = temp_dir("bundled-prune-root");
        Path stale_install_path = config_home.resolve("plugins/installed/stale-bundled-external");
        write_bundled_plugin(bundled_root.resolve("active"), "active", "0.1.0", false);
        write_file(
                stale_install_path.resolve(".claude-plugin/plugin.json"),
                "{\n  \"name\": \"stale\",\n  \"version\": \"0.1.0\",\n  \"description\": \"stale bundled plugin\"\n}");

        PluginManagerConfig config = new PluginManagerConfig(config_home)
                .with_bundled_root(bundled_root)
                .with_install_root(config_home.resolve("plugins/installed"));
        PluginManager manager = new PluginManager(config);

        InstalledPluginRegistry seeded = new InstalledPluginRegistry();
        seeded.put(
                "stale@bundled",
                new InstalledPluginRecord(
                        PluginKind.BUNDLED,
                        "stale@bundled",
                        "stale",
                        "0.1.0",
                        "stale bundled plugin",
                        stale_install_path,
                        new PluginInstallSource.LocalPath(bundled_root.resolve("stale")),
                        1L,
                        1L));
        manager.store_registry(seeded);
        manager.write_enabled_state("stale@bundled", java.util.Optional.of(true));

        var installed = manager.list_installed_plugins();
        assertThat(installed).anyMatch(p -> p.metadata().id().equals("active@bundled"));
        assertThat(installed).noneMatch(p -> p.metadata().id().equals("stale@bundled"));

        var registry = manager.load_registry();
        assertThat(registry.containsKey("stale@bundled")).isFalse();
        assertThat(Files.exists(stale_install_path)).isFalse();
    }

    @Test
    void installed_plugin_discovery_keeps_registry_entries_outside_install_root() throws Exception {
        Path config_home = temp_dir("registry-fallback-home");
        Path bundled_root = temp_dir("registry-fallback-bundled");
        Path install_root = config_home.resolve("plugins/installed");
        Path external_install_path = temp_dir("registry-fallback-external");
        write_file(
                external_install_path.resolve("plugin.json"),
                "{\n  \"name\": \"registry-fallback\",\n  \"version\": \"1.0.0\",\n  \"description\": \"Registry fallback plugin\"\n}");

        PluginManagerConfig config = new PluginManagerConfig(config_home)
                .with_bundled_root(bundled_root)
                .with_install_root(install_root);
        PluginManager manager = new PluginManager(config);

        InstalledPluginRegistry seeded = new InstalledPluginRegistry();
        seeded.put(
                "registry-fallback@external",
                new InstalledPluginRecord(
                        PluginKind.EXTERNAL,
                        "registry-fallback@external",
                        "registry-fallback",
                        "1.0.0",
                        "Registry fallback plugin",
                        external_install_path,
                        new PluginInstallSource.LocalPath(external_install_path),
                        1L,
                        1L));
        manager.store_registry(seeded);

        var installed = manager.list_installed_plugins();
        assertThat(installed).anyMatch(p -> p.metadata().id().equals("registry-fallback@external"));
    }

    @Test
    void installed_plugin_discovery_prunes_stale_registry_entries() throws Exception {
        Path config_home = temp_dir("registry-prune-home");
        Path bundled_root = temp_dir("registry-prune-bundled");
        Path install_root = config_home.resolve("plugins/installed");
        Path missing_install_path = temp_dir("registry-prune-missing");
        Files.delete(missing_install_path); // ensure missing

        PluginManagerConfig config = new PluginManagerConfig(config_home)
                .with_bundled_root(bundled_root)
                .with_install_root(install_root);
        PluginManager manager = new PluginManager(config);

        InstalledPluginRegistry seeded = new InstalledPluginRegistry();
        seeded.put(
                "stale-external@external",
                new InstalledPluginRecord(
                        PluginKind.EXTERNAL,
                        "stale-external@external",
                        "stale-external",
                        "1.0.0",
                        "stale external plugin",
                        missing_install_path,
                        new PluginInstallSource.LocalPath(missing_install_path),
                        1L,
                        1L));
        manager.store_registry(seeded);

        var installed = manager.list_installed_plugins();
        assertThat(installed).noneMatch(p -> p.metadata().id().equals("stale-external@external"));
        var registry = manager.load_registry();
        assertThat(registry.containsKey("stale-external@external")).isFalse();
    }

    @Test
    void persists_bundled_plugin_enable_state_across_reloads() throws Exception {
        Path config_home = temp_dir("bundled-state-home");
        Path bundled_root = temp_dir("bundled-state-root");
        write_bundled_plugin(bundled_root.resolve("starter"), "starter", "0.1.0", false);

        PluginManagerConfig config = new PluginManagerConfig(config_home).with_bundled_root(bundled_root);
        PluginManager manager = new PluginManager(config);
        manager.enable("starter@bundled");
        assertThat(PluginManager.load_enabled_plugins(manager.settings_path()).get("starter@bundled"))
                .isTrue();

        PluginManagerConfig reloaded = new PluginManagerConfig(config_home)
                .with_bundled_root(bundled_root)
                .with_enabled(PluginManager.load_enabled_plugins(manager.settings_path()));
        PluginManager reloaded_mgr = new PluginManager(reloaded);
        var listed = reloaded_mgr.list_installed_plugins();
        assertThat(listed).anyMatch(p -> p.metadata().id().equals("starter@bundled") && p.enabled());
    }

    @Test
    void persists_bundled_plugin_disable_state_across_reloads() throws Exception {
        Path config_home = temp_dir("bundled-disabled-home");
        Path bundled_root = temp_dir("bundled-disabled-root");
        write_bundled_plugin(bundled_root.resolve("starter"), "starter", "0.1.0", true);

        PluginManagerConfig config = new PluginManagerConfig(config_home).with_bundled_root(bundled_root);
        PluginManager manager = new PluginManager(config);
        manager.disable("starter@bundled");
        assertThat(PluginManager.load_enabled_plugins(manager.settings_path()).get("starter@bundled"))
                .isFalse();

        PluginManagerConfig reloaded = new PluginManagerConfig(config_home)
                .with_bundled_root(bundled_root)
                .with_enabled(PluginManager.load_enabled_plugins(manager.settings_path()));
        var listed = new PluginManager(reloaded).list_installed_plugins();
        assertThat(listed).anyMatch(p -> p.metadata().id().equals("starter@bundled") && !p.enabled());
    }

    @Test
    void validates_plugin_source_before_install() throws Exception {
        Path config_home = temp_dir("validate-home");
        Path source_root = temp_dir("validate-source");
        write_external_plugin(source_root, "validator", "1.0.0");
        PluginManager manager = new PluginManager(empty_config(config_home));
        PluginManifest manifest = manager.validate_plugin_source(source_root.toString());
        assertThat(manifest.name()).isEqualTo("validator");
    }

    @Test
    void plugin_registry_tracks_enabled_state_and_lookup() throws Exception {
        Path config_home = temp_dir("registry-home");
        Path source_root = temp_dir("registry-source");
        write_external_plugin(source_root, "registry-demo", "1.0.0");

        PluginManager manager = new PluginManager(empty_config(config_home));
        manager.install(source_root.toString());
        manager.disable("registry-demo@external");

        var registry = manager.plugin_registry();
        var plugin = registry.get("registry-demo@external");
        assertThat(plugin).isPresent();
        assertThat(plugin.get().metadata().name()).isEqualTo("registry-demo");
        assertThat(plugin.get().is_enabled()).isFalse();
        assertThat(registry.contains("registry-demo@external")).isTrue();
        assertThat(registry.contains("missing@external")).isFalse();
    }

    @Test
    void plugin_registry_report_collects_load_failures_without_dropping_valid_plugins() throws Exception {
        Path config_home = temp_dir("report-home");
        Path external_root = temp_dir("report-external");
        write_external_plugin(external_root.resolve("valid"), "valid-report", "1.0.0");
        write_broken_plugin(external_root.resolve("broken"), "broken-report");

        PluginManagerConfig config = empty_config(config_home);
        config.external_dirs().add(external_root);
        PluginManager manager = new PluginManager(config);

        PluginRegistryReport report = manager.plugin_registry_report();
        assertThat(report.registry().contains("valid-report@external")).isTrue();
        assertThat(report.failures()).hasSize(1);
        assertThat(report.failures().get(0).kind()).isEqualTo(PluginKind.EXTERNAL);
        assertThat(report.failures().get(0).plugin_root().toString()).endsWith("broken");
        assertThat(report.failures().get(0).error().getMessage()).contains("does not exist");

        assertThatThrownBy(manager::plugin_registry)
                .isInstanceOf(PluginError.class)
                .satisfies(error -> {
                    PluginError pe = (PluginError) error;
                    assertThat(pe.kind()).isEqualTo(PluginError.Kind.LOAD_FAILURES);
                    assertThat(pe.load_failures_list()).hasSize(1);
                });
    }

    @Test
    void installed_plugin_registry_report_collects_load_failures_from_install_root() throws Exception {
        Path config_home = temp_dir("installed-report-home");
        Path bundled_root = temp_dir("installed-report-bundled");
        Path install_root = config_home.resolve("plugins/installed");
        write_external_plugin(install_root.resolve("valid"), "installed-valid", "1.0.0");
        write_broken_plugin(install_root.resolve("broken"), "installed-broken");

        PluginManagerConfig config = new PluginManagerConfig(config_home)
                .with_bundled_root(bundled_root)
                .with_install_root(install_root);
        PluginManager manager = new PluginManager(config);
        PluginRegistryReport report = manager.installed_plugin_registry_report();
        assertThat(report.registry().contains("installed-valid@external")).isTrue();
        assertThat(report.failures()).hasSize(1);
    }

    @Test
    void rejects_plugin_sources_with_missing_hook_paths() throws Exception {
        Path config_home = temp_dir("broken-home");
        Path source_root = temp_dir("broken-source");
        write_broken_plugin(source_root, "broken");
        PluginManager manager = new PluginManager(empty_config(config_home));
        assertThatThrownBy(() -> manager.validate_plugin_source(source_root.toString()))
                .hasMessageContaining("does not exist");
        assertThatThrownBy(() -> manager.install(source_root.toString())).hasMessageContaining("does not exist");
    }

    @Test
    void rejects_plugin_sources_with_missing_failure_hook_paths() throws Exception {
        Path config_home = temp_dir("broken-failure-home");
        Path source_root = temp_dir("broken-failure-source");
        write_broken_failure_hook_plugin(source_root, "broken-failure");
        PluginManager manager = new PluginManager(empty_config(config_home));
        assertThatThrownBy(() -> manager.validate_plugin_source(source_root.toString()))
                .hasMessageContaining("does not exist");
        assertThatThrownBy(() -> manager.install(source_root.toString())).hasMessageContaining("does not exist");
    }

    @Test
    void plugin_registry_runs_initialize_and_shutdown_for_enabled_plugins() throws Exception {
        Path config_home = temp_dir("lifecycle-home");
        Path source_root = temp_dir("lifecycle-source");
        write_lifecycle_plugin(source_root, "lifecycle-demo", "1.0.0");
        PluginManager manager = new PluginManager(empty_config(config_home));
        InstallOutcome install = manager.install(source_root.toString());
        Path log_path = install.install_path().resolve("lifecycle.log");

        var registry = manager.plugin_registry();
        registry.initialize();
        registry.shutdown();
        String log = Files.readString(log_path);
        assertThat(log).isEqualTo("init\nshutdown\n");
    }

    @Test
    void aggregates_and_executes_plugin_tools() throws Exception {
        Path config_home = temp_dir("tool-home");
        Path source_root = temp_dir("tool-source");
        write_tool_plugin(source_root, "tool-demo", "1.0.0");
        PluginManager manager = new PluginManager(empty_config(config_home));
        manager.install(source_root.toString());

        var tools = manager.aggregated_tools();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).definition().name()).isEqualTo("plugin_echo");
        assertThat(tools.get(0).required_permission()).isEqualTo("workspace-write");

        String output = tools.get(0).execute(MAPPER.readTree("{\"message\":\"hello\"}"));
        var payload = MAPPER.readTree(output);
        assertThat(payload.path("plugin").asText()).isEqualTo("tool-demo@external");
        assertThat(payload.path("tool").asText()).isEqualTo("plugin_echo");
        assertThat(payload.path("input").path("message").asText()).isEqualTo("hello");
    }

    @Test
    void list_installed_plugins_scans_install_root_without_registry_entries() throws Exception {
        Path config_home = temp_dir("installed-scan-home");
        Path bundled_root = temp_dir("installed-scan-bundled");
        Path install_root = config_home.resolve("plugins/installed");
        Path installed_plugin_root = install_root.resolve("scan-demo");
        write_file(
                installed_plugin_root.resolve("plugin.json"),
                "{\n  \"name\": \"scan-demo\",\n  \"version\": \"1.0.0\",\n  \"description\": \"Scanned from install root\"\n}");

        PluginManagerConfig config = new PluginManagerConfig(config_home)
                .with_bundled_root(bundled_root)
                .with_install_root(install_root);
        PluginManager manager = new PluginManager(config);
        var installed = manager.list_installed_plugins();
        assertThat(installed).anyMatch(p -> p.metadata().id().equals("scan-demo@external"));
    }

    @Test
    void list_installed_plugins_scans_packaged_manifests_in_install_root() throws Exception {
        Path config_home = temp_dir("installed-packaged-scan-home");
        Path bundled_root = temp_dir("installed-packaged-scan-bundled");
        Path install_root = config_home.resolve("plugins/installed");
        Path installed_plugin_root = install_root.resolve("scan-packaged");
        write_file(
                installed_plugin_root.resolve(".claude-plugin/plugin.json"),
                "{\n  \"name\": \"scan-packaged\",\n  \"version\": \"1.0.0\",\n  \"description\": \"Packaged manifest in install root\"\n}");

        PluginManagerConfig config = new PluginManagerConfig(config_home)
                .with_bundled_root(bundled_root)
                .with_install_root(install_root);
        PluginManager manager = new PluginManager(config);
        var installed = manager.list_installed_plugins();
        assertThat(installed).anyMatch(p -> p.metadata().id().equals("scan-packaged@external"));
    }

    @Test
    void claw_config_home_isolation_prevents_host_plugin_leakage() throws Exception {
        Path config_home = temp_dir("isolated-home");
        Path bundled_root = temp_dir("isolated-bundled");
        Path install_root = config_home.resolve("plugins/installed");
        Path fixture_plugin_root = install_root.resolve("isolated-test-plugin");
        write_file(
                fixture_plugin_root.resolve(".claude-plugin/plugin.json"),
                "{\n  \"name\": \"isolated-test-plugin\",\n  \"version\": \"1.0.0\",\n  \"description\": \"Test fixture plugin in isolated config home\"\n}");

        PluginManagerConfig config = new PluginManagerConfig(config_home).with_bundled_root(bundled_root);
        PluginManager manager = new PluginManager(config);
        var installed = manager.list_installed_plugins();
        assertThat(installed).hasSize(1);
        assertThat(installed.get(0).metadata().id()).isEqualTo("isolated-test-plugin@external");
    }

    @Test
    void plugin_lifecycle_handles_parallel_execution() throws Exception {
        Path base_dir = temp_dir("parallel-base");

        java.util.concurrent.atomic.AtomicInteger success_count = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger error_count = new java.util.concurrent.atomic.AtomicInteger();

        java.util.List<Thread> handles = new java.util.ArrayList<>();
        for (int thread_id = 0; thread_id < 5; thread_id++) {
            final int id = thread_id;
            Thread handle = new Thread(() -> {
                try {
                    Path config_home = base_dir.resolve("config-" + id);
                    Path source_root = base_dir.resolve("source-" + id);
                    Files.createDirectories(config_home);
                    Files.createDirectories(source_root);

                    write_lifecycle_plugin(source_root, "parallel-" + id, "1.0.0");

                    Path bundled = base_dir.resolve("bundled-" + id);
                    Files.createDirectories(bundled);
                    PluginManagerConfig config = new PluginManagerConfig(config_home).with_bundled_root(bundled);
                    PluginManager manager = new PluginManager(config);
                    InstallOutcome install = manager.install(source_root.toString());

                    Path log_path = install.install_path().resolve("lifecycle.log");
                    var registry = manager.plugin_registry();
                    registry.initialize();
                    registry.shutdown();

                    if (Files.exists(log_path)) {
                        String log = Files.readString(log_path);
                        if (log.equals("init\nshutdown\n")) {
                            success_count.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    error_count.incrementAndGet();
                }
            });
            handles.add(handle);
            handle.start();
        }

        for (Thread handle : handles) {
            handle.join();
        }

        assertThat(success_count.get()).isEqualTo(5);
        assertThat(error_count.get()).isZero();
    }
}
