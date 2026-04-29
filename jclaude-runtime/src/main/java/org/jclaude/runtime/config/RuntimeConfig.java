package org.jclaude.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Fully merged runtime configuration. */
public final class RuntimeConfig {

    public static final String CLAW_SETTINGS_SCHEMA_NAME = "SettingsSchema";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, JsonNode> merged;
    private final List<ConfigEntry> loaded_entries;
    private final RuntimeHookConfig hooks;
    private final RuntimePermissionRuleConfig permission_rules;
    private final RuntimePluginConfig plugins;
    private final ProviderFallbackConfig provider_fallbacks;
    private final List<String> trusted_roots;
    private final Optional<String> model;
    private final Optional<ResolvedPermissionMode> permission_mode;

    public RuntimeConfig(
            Map<String, JsonNode> merged,
            List<ConfigEntry> loaded_entries,
            RuntimeHookConfig hooks,
            RuntimePermissionRuleConfig permission_rules,
            RuntimePluginConfig plugins,
            ProviderFallbackConfig provider_fallbacks,
            List<String> trusted_roots,
            Optional<String> model,
            Optional<ResolvedPermissionMode> permission_mode) {
        this.merged = Map.copyOf(merged);
        this.loaded_entries = List.copyOf(loaded_entries);
        this.hooks = hooks;
        this.permission_rules = permission_rules;
        this.plugins = plugins;
        this.provider_fallbacks = provider_fallbacks;
        this.trusted_roots = List.copyOf(trusted_roots);
        this.model = model;
        this.permission_mode = permission_mode;
    }

    public Map<String, JsonNode> merged() {
        return merged;
    }

    public List<ConfigEntry> loaded_entries() {
        return loaded_entries;
    }

    public RuntimeHookConfig hooks() {
        return hooks;
    }

    public RuntimePermissionRuleConfig permission_rules() {
        return permission_rules;
    }

    public RuntimePluginConfig plugins() {
        return plugins;
    }

    public ProviderFallbackConfig provider_fallbacks() {
        return provider_fallbacks;
    }

    public List<String> trusted_roots() {
        return trusted_roots;
    }

    public Optional<String> model() {
        return model;
    }

    public Optional<ResolvedPermissionMode> permission_mode() {
        return permission_mode;
    }

    public static RuntimeConfig empty() {
        return new RuntimeConfig(
                Map.of(),
                List.of(),
                RuntimeHookConfig.empty(),
                RuntimePermissionRuleConfig.empty(),
                RuntimePluginConfig.empty(),
                ProviderFallbackConfig.empty(),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    /** Load and merge config files in [user, project, local] precedence. */
    public static RuntimeConfig load(List<ConfigEntry> entries) throws IOException {
        Map<String, JsonNode> merged = new LinkedHashMap<>();
        List<ConfigEntry> loaded = new ArrayList<>();
        for (ConfigEntry entry : entries) {
            if (!Files.exists(entry.path())) {
                continue;
            }
            String content = Files.readString(entry.path(), StandardCharsets.UTF_8);
            if (content.trim().isEmpty()) {
                loaded.add(entry);
                continue;
            }
            JsonNode parsed = JSON.readTree(content);
            if (!parsed.isObject()) {
                throw new ConfigValidationError("settings file must be a JSON object: " + entry.path());
            }
            parsed.fieldNames().forEachRemaining(name -> merged.put(name, parsed.get(name)));
            loaded.add(entry);
        }
        return new RuntimeConfig(
                merged,
                loaded,
                extract_hooks(merged),
                extract_permissions(merged),
                RuntimePluginConfig.empty(),
                ProviderFallbackConfig.empty(),
                extract_strings(merged.get("trustedRoots")),
                Optional.ofNullable(string_or_null(merged.get("model"))),
                Optional.ofNullable(parse_permission_mode(merged.get("permissionMode"))));
    }

    private static String string_or_null(JsonNode n) {
        return n != null && n.isTextual() ? n.asText() : null;
    }

    private static List<String> extract_strings(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        arr.forEach(n -> {
            if (n.isTextual()) {
                out.add(n.asText());
            }
        });
        return Collections.unmodifiableList(out);
    }

    private static ResolvedPermissionMode parse_permission_mode(JsonNode n) {
        if (n == null || !n.isTextual()) {
            return null;
        }
        String s = n.asText();
        return switch (s) {
            case "read-only", "readOnly" -> ResolvedPermissionMode.READ_ONLY;
            case "workspace-write", "workspaceWrite" -> ResolvedPermissionMode.WORKSPACE_WRITE;
            case "danger-full-access", "dangerFullAccess" -> ResolvedPermissionMode.DANGER_FULL_ACCESS;
            default -> null;
        };
    }

    private static RuntimeHookConfig extract_hooks(Map<String, JsonNode> merged) {
        JsonNode hooks = merged.get("hooks");
        if (hooks == null || !hooks.isObject()) {
            return RuntimeHookConfig.empty();
        }
        return new RuntimeHookConfig(
                extract_strings(hooks.get("PreToolUse")),
                extract_strings(hooks.get("PostToolUse")),
                extract_strings(hooks.get("PostToolUseFailure")));
    }

    private static RuntimePermissionRuleConfig extract_permissions(Map<String, JsonNode> merged) {
        JsonNode permissions = merged.get("permissions");
        if (permissions == null || !permissions.isObject()) {
            return RuntimePermissionRuleConfig.empty();
        }
        return new RuntimePermissionRuleConfig(
                extract_strings(permissions.get("allow")),
                extract_strings(permissions.get("deny")),
                extract_strings(permissions.get("ask")));
    }
}
