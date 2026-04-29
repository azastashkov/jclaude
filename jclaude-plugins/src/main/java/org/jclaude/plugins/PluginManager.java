package org.jclaude.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lifecycle controller for plugins — port of Rust's {@code PluginManager}. Discovers builtin /
 * bundled / external plugins, persists installed records to {@code installed.json}, mutates {@code
 * settings.json} for enable/disable state, and runs the install / uninstall / update / enable /
 * disable commands.
 */
public final class PluginManager {

    static final String EXTERNAL_MARKETPLACE = "external";
    static final String BUILTIN_MARKETPLACE = "builtin";
    static final String BUNDLED_MARKETPLACE = "bundled";
    static final String SETTINGS_FILE_NAME = "settings.json";
    static final String REGISTRY_FILE_NAME = "installed.json";
    static final String MANIFEST_FILE_NAME = "plugin.json";
    static final String MANIFEST_RELATIVE_PATH = ".claude-plugin/plugin.json";

    private static final ObjectMapper MAPPER;
    private static final AtomicLong MATERIALIZE_COUNTER = new AtomicLong(0);
    private static volatile Path BUNDLED_OVERRIDE = null;

    static {
        ObjectMapper m = new ObjectMapper();
        m.enable(SerializationFeature.INDENT_OUTPUT);
        MAPPER = m;
    }

    private final PluginManagerConfig config;

    public PluginManager(PluginManagerConfig config) {
        this.config = config;
    }

    public PluginManagerConfig config() {
        return config;
    }

    public Path install_root() {
        return config.install_root()
                .orElseGet(() -> config.config_home().resolve("plugins").resolve("installed"));
    }

    public Path registry_path() {
        return config.registry_path()
                .orElseGet(() -> config.config_home().resolve("plugins").resolve(REGISTRY_FILE_NAME));
    }

    public Path settings_path() {
        return config.config_home().resolve(SETTINGS_FILE_NAME);
    }

    public PluginRegistry plugin_registry() throws PluginError {
        return plugin_registry_report().into_registry();
    }

    public PluginRegistryReport plugin_registry_report() throws PluginError {
        sync_bundled_plugins();
        List<PluginDefinition> plugins = new ArrayList<>(builtin_plugins());
        List<PluginLoadFailure> failures = new ArrayList<>();
        Discovery installed = discover_installed_plugins_with_failures();
        plugins.addAll(installed.plugins);
        failures.addAll(installed.failures);
        Discovery external = discover_external_directory_plugins_with_failures(plugins);
        plugins.addAll(external.plugins);
        failures.addAll(external.failures);
        return build_registry_report(plugins, failures);
    }

    public List<PluginSummary> list_plugins() throws PluginError {
        return plugin_registry().summaries();
    }

    public List<PluginSummary> list_installed_plugins() throws PluginError {
        return installed_plugin_registry().summaries();
    }

    public PluginHooks aggregated_hooks() throws PluginError {
        return plugin_registry().aggregated_hooks();
    }

    public List<PluginTool> aggregated_tools() throws PluginError {
        return plugin_registry().aggregated_tools();
    }

    public PluginManifest validate_plugin_source(String source) throws PluginError {
        Path path = resolve_local_source(source);
        return load_plugin_from_directory(path);
    }

    public InstallOutcome install(String source) throws PluginError {
        PluginInstallSource install_source = parse_install_source(source);
        Path temp_root = install_root().resolve(".tmp");
        Path staged = materialize_source(install_source, temp_root);
        boolean cleanup_source = install_source instanceof PluginInstallSource.GitUrl;
        PluginManifest manifest = load_plugin_from_directory(staged);

        String plugin_id = plugin_id(manifest.name(), EXTERNAL_MARKETPLACE);
        Path install_path = install_root().resolve(sanitize_plugin_id(plugin_id));
        try {
            if (Files.exists(install_path)) {
                delete_recursively(install_path);
            }
            copy_dir_all(staged, install_path);
            if (cleanup_source) {
                try {
                    delete_recursively(staged);
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        } catch (IOException e) {
            throw PluginError.io(e);
        }

        long now = System.currentTimeMillis();
        InstalledPluginRecord record = new InstalledPluginRecord(
                PluginKind.EXTERNAL,
                plugin_id,
                manifest.name(),
                manifest.version(),
                manifest.description(),
                install_path,
                install_source,
                now,
                now);
        InstalledPluginRegistry registry = load_registry();
        registry.put(plugin_id, record);
        store_registry(registry);
        write_enabled_state(plugin_id, Optional.of(true));
        config.enabled_plugins().put(plugin_id, true);
        return new InstallOutcome(plugin_id, manifest.version(), install_path);
    }

    public void enable(String plugin_id) throws PluginError {
        ensure_known_plugin(plugin_id);
        write_enabled_state(plugin_id, Optional.of(true));
        config.enabled_plugins().put(plugin_id, true);
    }

    public void disable(String plugin_id) throws PluginError {
        ensure_known_plugin(plugin_id);
        write_enabled_state(plugin_id, Optional.of(false));
        config.enabled_plugins().put(plugin_id, false);
    }

    public void uninstall(String plugin_id) throws PluginError {
        InstalledPluginRegistry registry = load_registry();
        InstalledPluginRecord record = registry.remove(plugin_id);
        if (record == null) {
            throw PluginError.not_found("plugin `" + plugin_id + "` is not installed");
        }
        if (record.kind() == PluginKind.BUNDLED) {
            registry.put(plugin_id, record);
            throw PluginError.command_failed(
                    "plugin `" + plugin_id + "` is bundled and managed automatically; disable it instead");
        }
        try {
            if (Files.exists(record.install_path())) {
                delete_recursively(record.install_path());
            }
        } catch (IOException e) {
            throw PluginError.io(e);
        }
        store_registry(registry);
        write_enabled_state(plugin_id, Optional.empty());
        config.enabled_plugins().remove(plugin_id);
    }

    public UpdateOutcome update(String plugin_id) throws PluginError {
        InstalledPluginRegistry registry = load_registry();
        InstalledPluginRecord record = registry.get(plugin_id);
        if (record == null) {
            throw PluginError.not_found("plugin `" + plugin_id + "` is not installed");
        }
        Path temp_root = install_root().resolve(".tmp");
        Path staged = materialize_source(record.source(), temp_root);
        boolean cleanup_source = record.source() instanceof PluginInstallSource.GitUrl;
        PluginManifest manifest = load_plugin_from_directory(staged);
        try {
            if (Files.exists(record.install_path())) {
                delete_recursively(record.install_path());
            }
            copy_dir_all(staged, record.install_path());
            if (cleanup_source) {
                try {
                    delete_recursively(staged);
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        } catch (IOException e) {
            throw PluginError.io(e);
        }
        InstalledPluginRecord updated = new InstalledPluginRecord(
                record.kind(),
                record.id(),
                record.name(),
                manifest.version(),
                manifest.description(),
                record.install_path(),
                record.source(),
                record.installed_at_unix_ms(),
                System.currentTimeMillis());
        registry.put(plugin_id, updated);
        store_registry(registry);
        return new UpdateOutcome(plugin_id, record.version(), manifest.version(), record.install_path());
    }

    // ----- Discovery -----

    private static final class Discovery {
        final List<PluginDefinition> plugins = new ArrayList<>();
        final List<PluginLoadFailure> failures = new ArrayList<>();

        void extend(Discovery other) {
            plugins.addAll(other.plugins);
            failures.addAll(other.failures);
        }
    }

    private Discovery discover_installed_plugins_with_failures() throws PluginError {
        InstalledPluginRegistry registry = load_registry();
        Discovery discovery = new Discovery();
        Set<String> seen_ids = new HashSet<>();
        Set<Path> seen_paths = new HashSet<>();
        List<String> stale_ids = new ArrayList<>();

        for (Path install_path : discover_plugin_dirs(install_root())) {
            InstalledPluginRecord matched = null;
            for (InstalledPluginRecord r : registry.values()) {
                if (r.install_path().equals(install_path)) {
                    matched = r;
                    break;
                }
            }
            PluginKind kind = matched == null ? PluginKind.EXTERNAL : matched.kind();
            String source =
                    matched == null ? install_path.toString() : matched.source().describe();
            try {
                PluginDefinition plugin = load_plugin_definition(install_path, kind, source, kind.marketplace());
                if (seen_ids.add(plugin.metadata().id())) {
                    seen_paths.add(install_path);
                    discovery.plugins.add(plugin);
                }
            } catch (PluginError error) {
                discovery.failures.add(new PluginLoadFailure(install_path, kind, source, error));
            }
        }

        for (InstalledPluginRecord record : registry.values()) {
            if (seen_paths.contains(record.install_path())) {
                continue;
            }
            if (!Files.exists(record.install_path()) || !manifest_path_exists(record.install_path())) {
                stale_ids.add(record.id());
                continue;
            }
            String source = record.source().describe();
            try {
                PluginDefinition plugin = load_plugin_definition(
                        record.install_path(),
                        record.kind(),
                        source,
                        record.kind().marketplace());
                if (seen_ids.add(plugin.metadata().id())) {
                    seen_paths.add(record.install_path());
                    discovery.plugins.add(plugin);
                }
            } catch (PluginError error) {
                discovery.failures.add(new PluginLoadFailure(record.install_path(), record.kind(), source, error));
            }
        }

        if (!stale_ids.isEmpty()) {
            for (String id : stale_ids) {
                registry.remove(id);
            }
            store_registry(registry);
        }
        return discovery;
    }

    private Discovery discover_external_directory_plugins_with_failures(List<PluginDefinition> existing)
            throws PluginError {
        Discovery discovery = new Discovery();
        for (Path dir : config.external_dirs()) {
            for (Path root : discover_plugin_dirs(dir)) {
                String source = root.toString();
                try {
                    PluginDefinition plugin =
                            load_plugin_definition(root, PluginKind.EXTERNAL, source, EXTERNAL_MARKETPLACE);
                    boolean duplicate = false;
                    for (PluginDefinition seen : existing) {
                        if (seen.metadata().id().equals(plugin.metadata().id())) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) {
                        for (PluginDefinition seen : discovery.plugins) {
                            if (seen.metadata().id().equals(plugin.metadata().id())) {
                                duplicate = true;
                                break;
                            }
                        }
                    }
                    if (!duplicate) {
                        discovery.plugins.add(plugin);
                    }
                } catch (PluginError error) {
                    discovery.failures.add(new PluginLoadFailure(root, PluginKind.EXTERNAL, source, error));
                }
            }
        }
        return discovery;
    }

    public PluginRegistryReport installed_plugin_registry_report() throws PluginError {
        sync_bundled_plugins();
        Discovery installed = discover_installed_plugins_with_failures();
        return build_registry_report(installed.plugins, installed.failures);
    }

    private PluginRegistry installed_plugin_registry() throws PluginError {
        return installed_plugin_registry_report().into_registry();
    }

    private PluginRegistryReport build_registry_report(
            List<PluginDefinition> definitions, List<PluginLoadFailure> failures) {
        List<RegisteredPlugin> registered = new ArrayList<>(definitions.size());
        for (PluginDefinition definition : definitions) {
            registered.add(new RegisteredPlugin(definition, is_enabled(definition.metadata())));
        }
        return new PluginRegistryReport(PluginRegistry.of(registered), failures);
    }

    private boolean is_enabled(PluginMetadata metadata) {
        Boolean override = config.enabled_plugins().get(metadata.id());
        if (override != null) {
            return override;
        }
        return switch (metadata.kind()) {
            case EXTERNAL -> false;
            case BUILTIN, BUNDLED -> metadata.default_enabled();
        };
    }

    private void ensure_known_plugin(String plugin_id) throws PluginError {
        if (!plugin_registry().contains(plugin_id)) {
            throw PluginError.not_found("plugin `" + plugin_id + "` is not installed or discoverable");
        }
    }

    // ----- Bundled plugin sync -----

    private void sync_bundled_plugins() throws PluginError {
        Path bundled_root = config.bundled_root().orElseGet(PluginManager::default_bundled_root);
        if (bundled_root == null) {
            return;
        }
        List<Path> bundled_plugins = discover_plugin_dirs(bundled_root);
        InstalledPluginRegistry registry = load_registry();
        boolean changed = false;
        Path install_root = install_root();
        Set<String> active_ids = new TreeSet<>();
        for (Path source_root : bundled_plugins) {
            PluginManifest manifest = load_plugin_from_directory(source_root);
            String plugin_id = plugin_id(manifest.name(), BUNDLED_MARKETPLACE);
            active_ids.add(plugin_id);
            Path install_path = install_root.resolve(sanitize_plugin_id(plugin_id));
            long now = System.currentTimeMillis();
            InstalledPluginRecord existing = registry.get(plugin_id);
            boolean installed_copy_valid = false;
            if (Files.exists(install_path)) {
                try {
                    load_plugin_from_directory(install_path);
                    installed_copy_valid = true;
                } catch (PluginError ignored) {
                    installed_copy_valid = false;
                }
            }
            boolean needs_sync = existing == null
                    || existing.kind() != PluginKind.BUNDLED
                    || !existing.version().equals(manifest.version())
                    || !existing.name().equals(manifest.name())
                    || !existing.description().equals(manifest.description())
                    || !existing.install_path().equals(install_path)
                    || !Files.exists(existing.install_path())
                    || !installed_copy_valid;
            if (!needs_sync) {
                continue;
            }
            try {
                if (Files.exists(install_path)) {
                    delete_recursively(install_path);
                }
                copy_dir_all(source_root, install_path);
            } catch (IOException e) {
                throw PluginError.io(e);
            }
            long installed_at = existing == null ? now : existing.installed_at_unix_ms();
            registry.put(
                    plugin_id,
                    new InstalledPluginRecord(
                            PluginKind.BUNDLED,
                            plugin_id,
                            manifest.name(),
                            manifest.version(),
                            manifest.description(),
                            install_path,
                            new PluginInstallSource.LocalPath(source_root),
                            installed_at,
                            now));
            changed = true;
        }
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, InstalledPluginRecord> entry : registry.entries()) {
            InstalledPluginRecord r = entry.getValue();
            if (r.kind() == PluginKind.BUNDLED && !active_ids.contains(entry.getKey())) {
                stale.add(entry.getKey());
            }
        }
        for (String id : stale) {
            InstalledPluginRecord removed = registry.remove(id);
            if (removed != null && Files.exists(removed.install_path())) {
                try {
                    delete_recursively(removed.install_path());
                } catch (IOException e) {
                    throw PluginError.io(e);
                }
            }
            changed = true;
        }
        if (changed) {
            store_registry(registry);
        }
    }

    /**
     * Returns the directory holding bundled plugin scaffolds. Plugins shipped under {@code
     * src/main/resources/bundled/} are extracted on first call into a temp directory if loaded from
     * a JAR.
     */
    public static Path default_bundled_root() {
        Path override = BUNDLED_OVERRIDE;
        if (override != null) {
            return override;
        }
        synchronized (PluginManager.class) {
            if (BUNDLED_OVERRIDE != null) {
                return BUNDLED_OVERRIDE;
            }
            Path resolved = resolve_bundled_root();
            BUNDLED_OVERRIDE = resolved;
            return resolved;
        }
    }

    /** Visible for tests that want to override the bundled root globally. */
    public static void set_default_bundled_root(Path path) {
        BUNDLED_OVERRIDE = path;
    }

    private static Path resolve_bundled_root() {
        URL url = PluginManager.class.getClassLoader().getResource("bundled");
        if (url == null) {
            return null;
        }
        try {
            URI uri = url.toURI();
            String scheme = uri.getScheme();
            if ("file".equals(scheme)) {
                return Paths.get(uri);
            }
            // jar:file:...!/bundled — extract contents to a temp dir
            return extract_bundled_resources_to_temp();
        } catch (Exception e) {
            return null;
        }
    }

    private static Path extract_bundled_resources_to_temp() throws Exception {
        Path tempDir = Files.createTempDirectory("jclaude-bundled-");
        URL url = PluginManager.class.getClassLoader().getResource("bundled");
        if (url == null) {
            return tempDir;
        }
        URI uri = url.toURI();
        try (FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
            Path root = fs.getPath("/bundled");
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path target = tempDir.resolve(root.relativize(dir).toString());
                    Files.createDirectories(target);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path target = tempDir.resolve(root.relativize(file).toString());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                    if (file.getFileName().toString().endsWith(".sh")) {
                        target.toFile().setExecutable(true);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return tempDir;
    }

    // ----- Registry persistence (installed.json) -----

    public InstalledPluginRegistry load_registry() throws PluginError {
        Path path = registry_path();
        try {
            if (!Files.exists(path)) {
                return new InstalledPluginRegistry();
            }
            String contents = Files.readString(path);
            if (contents.trim().isEmpty()) {
                return new InstalledPluginRegistry();
            }
            JsonNode root = MAPPER.readTree(contents);
            return InstalledPluginRegistry.from_json(root);
        } catch (IOException e) {
            throw PluginError.io(e);
        }
    }

    public void store_registry(InstalledPluginRegistry registry) throws PluginError {
        Path path = registry_path();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, registry.to_json_string(MAPPER));
        } catch (IOException e) {
            throw PluginError.io(e);
        }
    }

    public void write_enabled_state(String plugin_id, Optional<Boolean> enabled) throws PluginError {
        update_settings_json(settings_path(), root -> {
            ObjectNode container = ensure_object(root, "enabledPlugins");
            if (enabled.isPresent()) {
                container.put(plugin_id, enabled.get());
            } else {
                container.remove(plugin_id);
            }
        });
    }

    // ----- Manifest loading & validation -----

    public static PluginManifest load_plugin_from_directory(Path root) throws PluginError {
        Path manifest_path = plugin_manifest_path(root);
        return load_manifest_from_path(root, manifest_path);
    }

    private static PluginManifest load_manifest_from_path(Path root, Path manifest_path) throws PluginError {
        String contents;
        try {
            contents = Files.readString(manifest_path);
        } catch (IOException e) {
            throw PluginError.not_found("plugin manifest not found at " + manifest_path + ": " + e.getMessage());
        }
        JsonNode raw_json;
        try {
            raw_json = MAPPER.readTree(contents);
        } catch (IOException e) {
            throw PluginError.json(e);
        }
        List<PluginManifestValidationError> compatibility_errors = detect_claude_code_manifest_contract_gaps(raw_json);
        if (!compatibility_errors.isEmpty()) {
            throw PluginError.manifest_validation(compatibility_errors);
        }
        return build_plugin_manifest(root, raw_json);
    }

    private static List<PluginManifestValidationError> detect_claude_code_manifest_contract_gaps(JsonNode raw) {
        if (!raw.isObject()) {
            return List.of();
        }
        List<PluginManifestValidationError> errors = new ArrayList<>();
        String[][] guidance = {
            {
                "skills",
                "plugin manifest field `skills` uses the Claude Code plugin contract; `claw` does not load plugin-managed skills and instead discovers skills from local roots such as `.claw/skills`, `.omc/skills`, `.agents/skills`, `~/.omc/skills`, and `~/.claude/skills/omc-learned`."
            },
            {
                "mcpServers",
                "plugin manifest field `mcpServers` uses the Claude Code plugin contract; `claw` does not import MCP servers from plugin manifests."
            },
            {
                "agents",
                "plugin manifest field `agents` uses the Claude Code plugin contract; `claw` does not load plugin-managed agent markdown catalogs from plugin manifests."
            }
        };
        for (String[] entry : guidance) {
            if (raw.has(entry[0])) {
                errors.add(new PluginManifestValidationError.UnsupportedManifestContract(entry[1]));
            }
        }
        JsonNode commands = raw.path("commands");
        if (commands.isArray()) {
            for (JsonNode c : commands) {
                if (c.isTextual()) {
                    errors.add(
                            new PluginManifestValidationError.UnsupportedManifestContract(
                                    "plugin manifest field `commands` uses Claude Code-style directory globs; `claw` slash dispatch is still built-in and does not load plugin slash command markdown files."));
                    break;
                }
            }
        }
        JsonNode hooks = raw.path("hooks");
        if (hooks.isObject()) {
            Iterator<String> names = hooks.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!name.equals("PreToolUse") && !name.equals("PostToolUse") && !name.equals("PostToolUseFailure")) {
                    errors.add(
                            new PluginManifestValidationError.UnsupportedManifestContract(
                                    "plugin hook `" + name
                                            + "` uses the Claude Code lifecycle contract; `claw` plugins currently support only PreToolUse, PostToolUse, and PostToolUseFailure."));
                }
            }
        }
        return errors;
    }

    private static Path plugin_manifest_path(Path root) throws PluginError {
        Path direct = root.resolve(MANIFEST_FILE_NAME);
        if (Files.exists(direct)) {
            return direct;
        }
        Path packaged = root.resolve(MANIFEST_RELATIVE_PATH);
        if (Files.exists(packaged)) {
            return packaged;
        }
        throw PluginError.not_found("plugin manifest not found at " + direct + " or " + packaged);
    }

    private static boolean manifest_path_exists(Path root) {
        return Files.exists(root.resolve(MANIFEST_FILE_NAME)) || Files.exists(root.resolve(MANIFEST_RELATIVE_PATH));
    }

    private static PluginManifest build_plugin_manifest(Path root, JsonNode raw) throws PluginError {
        List<PluginManifestValidationError> errors = new ArrayList<>();
        String name = string_or_default(raw.path("name"), "");
        String version = string_or_default(raw.path("version"), "");
        String description = string_or_default(raw.path("description"), "");
        boolean default_enabled = raw.path("defaultEnabled").asBoolean(false);
        validate_required_field("name", name, errors);
        validate_required_field("version", version, errors);
        validate_required_field("description", description, errors);

        List<PluginPermission> permissions = build_manifest_permissions(raw.path("permissions"), errors);

        PluginHooks hooks = read_hooks(raw.path("hooks"));
        validate_command_entries(root, hooks.pre_tool_use(), "hook", errors);
        validate_command_entries(root, hooks.post_tool_use(), "hook", errors);
        validate_command_entries(root, hooks.post_tool_use_failure(), "hook", errors);

        PluginLifecycle lifecycle = read_lifecycle(raw.path("lifecycle"));
        validate_command_entries(root, lifecycle.init(), "lifecycle command", errors);
        validate_command_entries(root, lifecycle.shutdown(), "lifecycle command", errors);

        List<PluginToolManifest> tools = build_manifest_tools(root, raw.path("tools"), errors);
        List<PluginCommandManifest> commands = build_manifest_commands(root, raw.path("commands"), errors);

        if (!errors.isEmpty()) {
            throw PluginError.manifest_validation(errors);
        }
        return new PluginManifest(
                name, version, description, permissions, default_enabled, hooks, lifecycle, tools, commands);
    }

    private static String string_or_default(JsonNode node, String fallback) {
        return node.isTextual() ? node.asText() : fallback;
    }

    private static void validate_required_field(
            String field, String value, List<PluginManifestValidationError> errors) {
        if (value == null || value.trim().isEmpty()) {
            errors.add(new PluginManifestValidationError.EmptyField(field));
        }
    }

    private static List<PluginPermission> build_manifest_permissions(
            JsonNode node, List<PluginManifestValidationError> errors) {
        List<PluginPermission> validated = new ArrayList<>();
        if (!node.isArray()) {
            return validated;
        }
        Set<String> seen = new TreeSet<>();
        for (JsonNode entry : node) {
            String value = entry.isTextual() ? entry.asText().trim() : "";
            if (value.isEmpty()) {
                errors.add(new PluginManifestValidationError.EmptyEntryField("permission", "value", Optional.empty()));
                continue;
            }
            if (!seen.add(value)) {
                errors.add(new PluginManifestValidationError.DuplicatePermission(value));
                continue;
            }
            Optional<PluginPermission> parsed = PluginPermission.parse(value);
            if (parsed.isPresent()) {
                validated.add(parsed.get());
            } else {
                errors.add(new PluginManifestValidationError.InvalidPermission(value));
            }
        }
        return validated;
    }

    private static PluginHooks read_hooks(JsonNode node) {
        if (!node.isObject()) {
            return PluginHooks.empty();
        }
        return new PluginHooks(
                read_string_array(node.path("PreToolUse")),
                read_string_array(node.path("PostToolUse")),
                read_string_array(node.path("PostToolUseFailure")));
    }

    private static PluginLifecycle read_lifecycle(JsonNode node) {
        if (!node.isObject()) {
            return PluginLifecycle.empty();
        }
        return new PluginLifecycle(read_string_array(node.path("Init")), read_string_array(node.path("Shutdown")));
    }

    private static List<String> read_string_array(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode entry : node) {
            if (entry.isTextual()) {
                out.add(entry.asText());
            }
        }
        return out;
    }

    private static List<PluginToolManifest> build_manifest_tools(
            Path root, JsonNode node, List<PluginManifestValidationError> errors) {
        List<PluginToolManifest> validated = new ArrayList<>();
        if (!node.isArray()) {
            return validated;
        }
        Set<String> seen = new TreeSet<>();
        for (JsonNode tool : node) {
            String name =
                    tool.path("name").isTextual() ? tool.path("name").asText().trim() : "";
            if (name.isEmpty()) {
                errors.add(new PluginManifestValidationError.EmptyEntryField("tool", "name", Optional.empty()));
                continue;
            }
            if (!seen.add(name)) {
                errors.add(new PluginManifestValidationError.DuplicateEntry("tool", name));
                continue;
            }
            String description = tool.path("description").isTextual()
                    ? tool.path("description").asText()
                    : "";
            if (description.trim().isEmpty()) {
                errors.add(new PluginManifestValidationError.EmptyEntryField("tool", "description", Optional.of(name)));
            }
            String command =
                    tool.path("command").isTextual() ? tool.path("command").asText() : "";
            if (command.trim().isEmpty()) {
                errors.add(new PluginManifestValidationError.EmptyEntryField("tool", "command", Optional.of(name)));
            } else {
                validate_command_entry(root, command, "tool", errors);
            }
            JsonNode schema = tool.path("inputSchema");
            if (!schema.isObject()) {
                errors.add(new PluginManifestValidationError.InvalidToolInputSchema(name));
            }
            JsonNode args_node = tool.path("args");
            List<String> args = new ArrayList<>();
            if (args_node.isArray()) {
                for (JsonNode a : args_node) {
                    if (a.isTextual()) {
                        args.add(a.asText());
                    }
                }
            }
            String permission_label = tool.path("requiredPermission").isTextual()
                    ? tool.path("requiredPermission").asText().trim()
                    : "danger-full-access";
            Optional<PluginToolPermission> parsed = PluginToolPermission.parse(permission_label);
            if (parsed.isEmpty()) {
                errors.add(new PluginManifestValidationError.InvalidToolRequiredPermission(name, permission_label));
                continue;
            }
            validated.add(new PluginToolManifest(name, description, schema, command, args, parsed.get()));
        }
        return validated;
    }

    private static List<PluginCommandManifest> build_manifest_commands(
            Path root, JsonNode node, List<PluginManifestValidationError> errors) {
        List<PluginCommandManifest> validated = new ArrayList<>();
        if (!node.isArray()) {
            return validated;
        }
        Set<String> seen = new TreeSet<>();
        for (JsonNode entry : node) {
            if (!entry.isObject()) {
                continue;
            }
            String name =
                    entry.path("name").isTextual() ? entry.path("name").asText().trim() : "";
            if (name.isEmpty()) {
                errors.add(new PluginManifestValidationError.EmptyEntryField("command", "name", Optional.empty()));
                continue;
            }
            if (!seen.add(name)) {
                errors.add(new PluginManifestValidationError.DuplicateEntry("command", name));
                continue;
            }
            String description = entry.path("description").isTextual()
                    ? entry.path("description").asText()
                    : "";
            if (description.trim().isEmpty()) {
                errors.add(
                        new PluginManifestValidationError.EmptyEntryField("command", "description", Optional.of(name)));
            }
            String command =
                    entry.path("command").isTextual() ? entry.path("command").asText() : "";
            if (command.trim().isEmpty()) {
                errors.add(new PluginManifestValidationError.EmptyEntryField("command", "command", Optional.of(name)));
            } else {
                validate_command_entry(root, command, "command", errors);
            }
            validated.add(new PluginCommandManifest(name, description, command));
        }
        return validated;
    }

    private static void validate_command_entries(
            Path root, List<String> entries, String kind, List<PluginManifestValidationError> errors) {
        for (String entry : entries) {
            validate_command_entry(root, entry, kind, errors);
        }
    }

    private static void validate_command_entry(
            Path root, String entry, String kind, List<PluginManifestValidationError> errors) {
        if (entry == null || entry.trim().isEmpty()) {
            errors.add(new PluginManifestValidationError.EmptyEntryField(kind, "command", Optional.empty()));
            return;
        }
        if (is_literal_command(entry)) {
            return;
        }
        Path path = Paths.get(entry).isAbsolute() ? Paths.get(entry) : root.resolve(entry);
        if (!Files.exists(path)) {
            errors.add(new PluginManifestValidationError.MissingPath(kind, path));
        } else if (!Files.isRegularFile(path)) {
            errors.add(new PluginManifestValidationError.PathIsDirectory(kind, path));
        }
    }

    static void validate_command_path(Path root, String entry, String kind) throws PluginError {
        if (is_literal_command(entry)) {
            return;
        }
        Path path = Paths.get(entry).isAbsolute() ? Paths.get(entry) : root.resolve(entry);
        if (!Files.exists(path)) {
            throw PluginError.invalid_manifest(kind + " path `" + path + "` does not exist");
        }
        if (!Files.isRegularFile(path)) {
            throw PluginError.invalid_manifest(kind + " path `" + path + "` must point to a file");
        }
    }

    static boolean is_literal_command(String entry) {
        if (entry == null) {
            return true;
        }
        return !entry.startsWith("./")
                && !entry.startsWith("../")
                && !Paths.get(entry).isAbsolute();
    }

    static String resolve_hook_entry(Path root, String entry) {
        if (is_literal_command(entry)) {
            return entry;
        }
        return root.resolve(entry).toString();
    }

    private static PluginHooks resolve_hooks(Path root, PluginHooks hooks) {
        return new PluginHooks(
                hooks.pre_tool_use().stream()
                        .map(e -> resolve_hook_entry(root, e))
                        .toList(),
                hooks.post_tool_use().stream()
                        .map(e -> resolve_hook_entry(root, e))
                        .toList(),
                hooks.post_tool_use_failure().stream()
                        .map(e -> resolve_hook_entry(root, e))
                        .toList());
    }

    private static PluginLifecycle resolve_lifecycle(Path root, PluginLifecycle lifecycle) {
        return new PluginLifecycle(
                lifecycle.init().stream().map(e -> resolve_hook_entry(root, e)).toList(),
                lifecycle.shutdown().stream()
                        .map(e -> resolve_hook_entry(root, e))
                        .toList());
    }

    private static List<PluginTool> resolve_tools(
            Path root, String plugin_id, String plugin_name, List<PluginToolManifest> tools) {
        List<PluginTool> result = new ArrayList<>(tools.size());
        for (PluginToolManifest tool : tools) {
            String resolved_command = resolve_hook_entry(root, tool.command());
            PluginToolDefinition definition = new PluginToolDefinition(
                    tool.name(),
                    tool.description(),
                    tool.input_schema(),
                    resolved_command,
                    tool.required_permission().as_str());
            result.add(
                    new PluginTool(plugin_id, plugin_name, root, definition, tool.args(), tool.required_permission()));
        }
        return result;
    }

    static void run_lifecycle_commands(
            PluginMetadata metadata, PluginLifecycle lifecycle, String phase, List<String> commands)
            throws PluginError {
        if (lifecycle.is_empty() || commands.isEmpty()) {
            return;
        }
        for (String command : commands) {
            ProcessBuilder pb;
            if (Files.exists(Paths.get(command))) {
                pb = is_windows() ? new ProcessBuilder("cmd", "/C", command) : new ProcessBuilder("sh", command);
            } else {
                pb = is_windows() ? new ProcessBuilder("cmd", "/C", command) : new ProcessBuilder("sh", "-lc", command);
            }
            metadata.root().ifPresent(p -> pb.directory(p.toFile()));
            try {
                Process process = pb.start();
                int exit = process.waitFor();
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                if (exit != 0) {
                    String detail = stderr.trim().isEmpty() ? ("exit status " + exit) : stderr.trim();
                    throw PluginError.command_failed(
                            "plugin `" + metadata.id() + "` " + phase + " failed for `" + command + "`: " + detail);
                }
            } catch (IOException e) {
                throw PluginError.io(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw PluginError.io(new IOException("interrupted while running lifecycle command", e));
            }
        }
    }

    private static boolean is_windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    // ----- Helpers / utilities -----

    private static PluginDefinition load_plugin_definition(
            Path root, PluginKind kind, String source, String marketplace) throws PluginError {
        PluginManifest manifest = load_plugin_from_directory(root);
        PluginMetadata metadata = new PluginMetadata(
                plugin_id(manifest.name(), marketplace),
                manifest.name(),
                manifest.version(),
                manifest.description(),
                kind,
                source,
                manifest.default_enabled(),
                Optional.of(root));
        PluginHooks hooks = resolve_hooks(root, manifest.hooks());
        PluginLifecycle lifecycle = resolve_lifecycle(root, manifest.lifecycle());
        List<PluginTool> tools = resolve_tools(root, metadata.id(), metadata.name(), manifest.tools());
        return switch (kind) {
            case BUILTIN -> new PluginDefinition.Builtin(metadata, hooks, lifecycle, tools);
            case BUNDLED -> new PluginDefinition.Bundled(metadata, hooks, lifecycle, tools);
            case EXTERNAL -> new PluginDefinition.External(metadata, hooks, lifecycle, tools);
        };
    }

    public static List<PluginDefinition> builtin_plugins() {
        PluginMetadata metadata = new PluginMetadata(
                plugin_id("example-builtin", BUILTIN_MARKETPLACE),
                "example-builtin",
                "0.1.0",
                "Example built-in plugin scaffold for the Java plugin system",
                PluginKind.BUILTIN,
                BUILTIN_MARKETPLACE,
                false,
                Optional.empty());
        return List.of(new PluginDefinition.Builtin(metadata, PluginHooks.empty(), PluginLifecycle.empty(), List.of()));
    }

    private static Path resolve_local_source(String source) throws PluginError {
        Path path = Paths.get(source);
        if (Files.exists(path)) {
            return path;
        }
        throw PluginError.not_found("plugin source `" + source + "` was not found");
    }

    private static PluginInstallSource parse_install_source(String source) throws PluginError {
        boolean is_remote = source.startsWith("http://") || source.startsWith("https://") || source.startsWith("git@");
        if (!is_remote) {
            int dot = source.lastIndexOf('.');
            if (dot >= 0) {
                String ext = source.substring(dot + 1);
                if (ext.equalsIgnoreCase("git")) {
                    is_remote = true;
                }
            }
        }
        if (is_remote) {
            return new PluginInstallSource.GitUrl(source);
        }
        return new PluginInstallSource.LocalPath(resolve_local_source(source));
    }

    private static Path materialize_source(PluginInstallSource source, Path temp_root) throws PluginError {
        try {
            Files.createDirectories(temp_root);
        } catch (IOException e) {
            throw PluginError.io(e);
        }
        return switch (source) {
            case PluginInstallSource.LocalPath local -> local.path();
            case PluginInstallSource.GitUrl git -> {
                long unique = MATERIALIZE_COUNTER.getAndIncrement();
                long nanos = System.nanoTime();
                Path destination = temp_root.resolve("plugin-" + nanos + "-" + unique);
                ProcessBuilder pb =
                        new ProcessBuilder("git", "clone", "--depth", "1", git.url(), destination.toString());
                try {
                    Process process = pb.start();
                    int exit = process.waitFor();
                    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                    if (exit != 0) {
                        throw PluginError.command_failed("git clone failed for `" + git.url() + "`: " + stderr.trim());
                    }
                } catch (IOException e) {
                    throw PluginError.io(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw PluginError.io(new IOException("interrupted during git clone", e));
                }
                yield destination;
            }
        };
    }

    private static List<Path> discover_plugin_dirs(Path root) throws PluginError {
        if (root == null || !Files.exists(root)) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry) && manifest_path_exists(entry)) {
                    paths.add(entry);
                }
            }
        } catch (IOException e) {
            throw PluginError.io(e);
        }
        Collections.sort(paths);
        return paths;
    }

    static String plugin_id(String name, String marketplace) {
        return name + "@" + marketplace;
    }

    private static String sanitize_plugin_id(String plugin_id) {
        StringBuilder sb = new StringBuilder(plugin_id.length());
        for (int i = 0; i < plugin_id.length(); i++) {
            char c = plugin_id.charAt(i);
            sb.append(c == '/' || c == '\\' || c == '@' || c == ':' ? '-' : c);
        }
        return sb.toString();
    }

    private static void copy_dir_all(Path source, Path destination) throws IOException {
        Files.createDirectories(destination);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
            for (Path entry : stream) {
                Path target = destination.resolve(entry.getFileName().toString());
                if (Files.isDirectory(entry)) {
                    copy_dir_all(entry, target);
                } else {
                    Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING);
                    if (entry.getFileName().toString().endsWith(".sh")) {
                        target.toFile().setExecutable(true);
                    }
                }
            }
        }
    }

    private static void delete_recursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void update_settings_json(Path path, java.util.function.Consumer<ObjectNode> update)
            throws PluginError {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            ObjectNode root;
            if (Files.exists(path)) {
                String contents = Files.readString(path);
                if (contents.trim().isEmpty()) {
                    root = MAPPER.createObjectNode();
                } else {
                    JsonNode parsed = MAPPER.readTree(contents);
                    if (!parsed.isObject()) {
                        throw PluginError.invalid_manifest("settings file " + path + " must contain a JSON object");
                    }
                    root = (ObjectNode) parsed;
                }
            } else {
                root = MAPPER.createObjectNode();
            }
            update.accept(root);
            Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            throw PluginError.io(e);
        }
    }

    private static ObjectNode ensure_object(ObjectNode root, String key) {
        JsonNode existing = root.get(key);
        if (existing != null && existing.isObject()) {
            return (ObjectNode) existing;
        }
        ObjectNode created = MAPPER.createObjectNode();
        root.set(key, created);
        return created;
    }

    /** Reads {@code settings.json} and returns its {@code enabledPlugins} map (visible for tests). */
    public static Map<String, Boolean> load_enabled_plugins(Path settings_path) throws PluginError {
        try {
            if (!Files.exists(settings_path)) {
                return Map.of();
            }
            JsonNode root = MAPPER.readTree(Files.readString(settings_path));
            JsonNode enabled = root.path("enabledPlugins");
            if (!enabled.isObject()) {
                return Map.of();
            }
            Map<String, Boolean> result = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = enabled.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (entry.getValue() != null && entry.getValue().isBoolean()) {
                    result.put(entry.getKey(), entry.getValue().asBoolean());
                }
            }
            return result;
        } catch (IOException e) {
            throw PluginError.io(e);
        }
    }
}
