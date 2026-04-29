package org.jclaude.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jclaude.api.providers.OpenAiCompatClient;
import org.jclaude.api.providers.OpenAiCompatConfig;
import org.jclaude.api.providers.ProviderKind;
import org.jclaude.api.providers.ProviderMetadata;
import org.jclaude.api.providers.Providers;
import org.jclaude.api.providers.anthropic.AnthropicClient;
import org.jclaude.api.providers.anthropic.AnthropicConfig;
import org.jclaude.api.providers.anthropic.OAuthCredentials;
import org.jclaude.cli.adapter.AnthropicRuntimeApiClient;
import org.jclaude.cli.adapter.OpenAiRuntimeApiClient;
import org.jclaude.cli.input.AllowAllPermissionPrompter;
import org.jclaude.cli.input.YesNoPermissionPrompter;
import org.jclaude.cli.render.JsonOutputRenderer;
import org.jclaude.cli.render.TextOutputRenderer;
import org.jclaude.plugins.PluginRegistry;
import org.jclaude.plugins.PluginTool;
import org.jclaude.runtime.conversation.ApiClient;
import org.jclaude.runtime.conversation.ConversationRuntime;
import org.jclaude.runtime.conversation.TurnSummary;
import org.jclaude.runtime.lsp.LspClient;
import org.jclaude.runtime.lsp.LspRegistry;
import org.jclaude.runtime.mcp.McpToolBridge;
import org.jclaude.runtime.permissions.PermissionMode;
import org.jclaude.runtime.permissions.PermissionPolicy;
import org.jclaude.runtime.permissions.PermissionPrompter;
import org.jclaude.runtime.session.Session;
import org.jclaude.runtime.task.TaskRegistry;
import org.jclaude.runtime.team.CronRegistry;
import org.jclaude.runtime.team.TeamRegistry;
import org.jclaude.runtime.worker.WorkerRegistry;
import org.jclaude.tools.GlobalToolRegistry;
import org.jclaude.tools.MvpToolSpecs;
import org.jclaude.tools.RuntimeToolExecutorAdapter;
import org.jclaude.tools.ToolDispatcher;
import org.jclaude.tools.ToolSpec;
import org.jclaude.tools.bridge.McpToolBridgeAdapter;

/**
 * Wires together the API client, runtime, dispatcher, session, permissions, and
 * renderers from a parsed {@link JclaudeCommand}, then runs a single turn.
 *
 * <p>Routes Anthropic-native models (any model id starting with {@code claude},
 * or any registered Anthropic alias) through {@link AnthropicRuntimeApiClient}
 * and OpenAI-compatible models (xAI, OpenAI, DashScope, Ollama, LM Studio,
 * OpenRouter, etc) through {@link OpenAiRuntimeApiClient}. The Anthropic path
 * resolves credentials in this order:
 * {@code ANTHROPIC_API_KEY} → {@code ANTHROPIC_AUTH_TOKEN} → saved OAuth token
 * (via {@link OAuthCredentials#load_access_token()}). The base URL respects
 * {@code ANTHROPIC_BASE_URL} when set, which is the hook the Phase 2 mock
 * service relies on to intercept all Anthropic traffic.
 */
public final class WireRunner {

    private final JclaudeCommand command;

    public WireRunner(JclaudeCommand command) {
        this.command = command;
    }

    public int run(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            System.err.println("error: missing prompt — pass `-p \"...\"` or a positional argument");
            return 2;
        }

        String resolved_model = Providers.resolve_model_alias(command.model());
        ProviderMetadata metadata =
                Providers.metadata_for_model(resolved_model).orElseGet(() -> fallback_metadata(resolved_model));

        long max_tokens =
                command.max_tokens() > 0 ? command.max_tokens() : Providers.max_tokens_for_model(resolved_model);

        PluginRegistry plugins = PluginRegistry.load_default();
        List<ToolSpec> tool_specs = build_tool_specs(command.allowed_tools_csv(), plugins);
        ApiClient api_adapter;
        if (metadata.provider() == ProviderKind.ANTHROPIC) {
            AnthropicClient anthropic_client = build_anthropic_client();
            if (anthropic_client == null) {
                return 2;
            }
            api_adapter = new AnthropicRuntimeApiClient(anthropic_client, resolved_model, max_tokens, tool_specs);
        } else {
            OpenAiCompatConfig config = config_for(metadata);
            Optional<String> api_key = Providers.read_env_non_empty(metadata.auth_env());
            if (api_key.isEmpty()) {
                System.err.println("error: missing API key in env var " + metadata.auth_env());
                return 2;
            }
            OpenAiCompatClient client = new OpenAiCompatClient(api_key.get(), config);
            api_adapter = new OpenAiRuntimeApiClient(client, resolved_model, max_tokens, tool_specs);
        }

        Path workspace_root = Paths.get("").toAbsolutePath();
        Optional<McpToolBridgeAdapter> mcp_bridge = build_mcp_bridge_adapter();
        Optional<WorkerRegistry> worker_registry = build_worker_registry();
        Optional<Map<String, LspClient>> lsp_clients = build_lsp_clients_by_language();
        ToolDispatcher dispatcher = new ToolDispatcher(
                workspace_root,
                new GlobalToolRegistry(tool_specs),
                org.jclaude.tools.TodoStore.global(),
                org.jclaude.tools.PlanModeState.global(),
                System.err,
                plugins,
                new TaskRegistry(),
                new TeamRegistry(),
                new CronRegistry(),
                new LspRegistry(),
                mcp_bridge,
                worker_registry,
                lsp_clients);
        RuntimeToolExecutorAdapter tool_executor = new RuntimeToolExecutorAdapter(dispatcher);

        PermissionMode runtime_mode = command.permission_mode_option().runtime();
        PermissionPolicy policy = build_policy(runtime_mode, command.allowed_tools_csv(), plugins);

        PermissionPrompter prompter = command.dangerously_skip_permissions()
                ? AllowAllPermissionPrompter.INSTANCE
                : new YesNoPermissionPrompter();

        Session session;
        if (command.resume() != null && !command.resume().isBlank()) {
            session = Session.load_from_path(Paths.get(command.resume())).with_workspace_root(workspace_root);
        } else {
            session = Session.create().with_workspace_root(workspace_root);
        }
        session.set_model(resolved_model);

        ConversationRuntime runtime = new ConversationRuntime(session, api_adapter, tool_executor, policy, List.of());
        TurnSummary summary;
        try {
            summary = runtime.run_turn(prompt, prompter);
        } catch (RuntimeException error) {
            System.err.println("error: " + (error.getMessage() == null ? error.toString() : error.getMessage()));
            return 1;
        }

        if (command.output_format() == OutputFormat.JSON) {
            new JsonOutputRenderer().render(summary, resolved_model);
        } else {
            new TextOutputRenderer().render(summary, command.compact());
        }
        return 0;
    }

    /**
     * Build an {@link AnthropicClient} from the current process environment.
     * Resolves {@code ANTHROPIC_API_KEY} and {@code ANTHROPIC_AUTH_TOKEN} —
     * falling back to {@link OAuthCredentials#load_access_token()} for the
     * bearer token when neither env var is set — and applies an
     * {@code ANTHROPIC_BASE_URL} override so the Phase 2 mock service can
     * intercept all traffic by pointing the env at its mock endpoint.
     */
    private static AnthropicClient build_anthropic_client() {
        Optional<String> api_key = Providers.read_env_non_empty("ANTHROPIC_API_KEY");
        Optional<String> auth_token =
                Providers.read_env_non_empty("ANTHROPIC_AUTH_TOKEN").or(OAuthCredentials::load_access_token);
        if (api_key.isEmpty() && auth_token.isEmpty()) {
            System.err.println(
                    "error: missing Anthropic credentials — set ANTHROPIC_API_KEY or ANTHROPIC_AUTH_TOKEN, or sign in to populate ~/.claude/credentials.json");
            return null;
        }
        AnthropicConfig config = AnthropicConfig.defaults();
        if (api_key.isPresent()) {
            config = config.with_api_key(api_key.get());
        }
        if (auth_token.isPresent()) {
            config = config.with_auth_token(auth_token.get());
        }
        Optional<String> base_url_override = Providers.read_env_non_empty("ANTHROPIC_BASE_URL");
        if (base_url_override.isPresent()) {
            config = config.with_base_url(base_url_override.get());
        }
        return new AnthropicClient(config);
    }

    private static ProviderMetadata fallback_metadata(String model) {
        // Detect provider via env vars when the model is not in the registry —
        // OPENAI_BASE_URL set is treated as an OpenAI-compatible local provider
        // (Ollama, LM Studio, vLLM, OpenRouter).
        ProviderKind kind = Providers.detect_provider_kind(model);
        return switch (kind) {
            case OPENAI -> new ProviderMetadata(
                    ProviderKind.OPENAI,
                    "OPENAI_API_KEY",
                    "OPENAI_BASE_URL",
                    OpenAiCompatClient.DEFAULT_OPENAI_BASE_URL);
            case XAI -> new ProviderMetadata(
                    ProviderKind.XAI, "XAI_API_KEY", "XAI_BASE_URL", OpenAiCompatClient.DEFAULT_XAI_BASE_URL);
            case ANTHROPIC -> new ProviderMetadata(
                    ProviderKind.ANTHROPIC,
                    "ANTHROPIC_API_KEY",
                    "ANTHROPIC_BASE_URL",
                    Providers.DEFAULT_ANTHROPIC_BASE_URL);
        };
    }

    private static OpenAiCompatConfig config_for(ProviderMetadata metadata) {
        // Pick the matching built-in config by env-var name so request-body
        // limits and provider names align.
        return switch (metadata.auth_env()) {
            case "XAI_API_KEY" -> OpenAiCompatConfig.xai();
            case "DASHSCOPE_API_KEY" -> OpenAiCompatConfig.dashscope();
            default -> OpenAiCompatConfig.openai();
        };
    }

    static List<ToolSpec> filtered_tool_specs(String allowed_csv) {
        List<ToolSpec> all = MvpToolSpecs.mvp_tool_specs();
        if (allowed_csv == null || allowed_csv.isBlank()) {
            return all;
        }
        Set<String> allowed = parse_csv(allowed_csv);
        List<ToolSpec> filtered = new ArrayList<>();
        for (ToolSpec spec : all) {
            if (allowed.contains(spec.name())) {
                filtered.add(spec);
            }
        }
        return filtered;
    }

    /**
     * Builds the active list of {@link ToolSpec}s the model sees: MVP tools filtered by {@code
     * --allowedTools} plus every plugin-loaded tool. Plugin tools are always included regardless of
     * the CSV filter — they're declared in {@code settings.json} explicitly.
     */
    static List<ToolSpec> build_tool_specs(String allowed_csv, PluginRegistry plugins) {
        List<ToolSpec> result = new ArrayList<>(filtered_tool_specs(allowed_csv));
        for (PluginTool tool : plugins.tools()) {
            String description = tool.definition().description() == null
                    ? "Plugin tool " + tool.definition().name()
                    : tool.definition().description();
            result.add(new ToolSpec(
                    tool.definition().name(), description, tool.definition().input_schema()));
        }
        return result;
    }

    static PermissionPolicy build_policy(PermissionMode mode, String allowed_csv) {
        return build_policy(mode, allowed_csv, PluginRegistry.empty());
    }

    static PermissionPolicy build_policy(PermissionMode mode, String allowed_csv, PluginRegistry plugins) {
        PermissionPolicy policy = PermissionPolicy.newPolicy(mode);
        // Tool requirement matrix mirrors the Rust defaults: writes need
        // workspace-write, bash needs danger-full-access.
        policy.with_tool_requirement("read_file", PermissionMode.READ_ONLY)
                .with_tool_requirement("glob_search", PermissionMode.READ_ONLY)
                .with_tool_requirement("grep_search", PermissionMode.READ_ONLY)
                .with_tool_requirement("write_file", PermissionMode.WORKSPACE_WRITE)
                .with_tool_requirement("edit_file", PermissionMode.WORKSPACE_WRITE)
                .with_tool_requirement("bash", PermissionMode.DANGER_FULL_ACCESS)
                .with_tool_requirement("TodoWrite", PermissionMode.READ_ONLY)
                .with_tool_requirement("Sleep", PermissionMode.READ_ONLY)
                .with_tool_requirement("ToolSearch", PermissionMode.READ_ONLY)
                .with_tool_requirement("StructuredOutput", PermissionMode.READ_ONLY)
                .with_tool_requirement("EnterPlanMode", PermissionMode.READ_ONLY)
                .with_tool_requirement("ExitPlanMode", PermissionMode.READ_ONLY)
                .with_tool_requirement("SendUserMessage", PermissionMode.READ_ONLY);

        // Plugin tools register their declared `requiredPermission` (defaults to read-only).
        for (PluginTool tool : plugins.tools()) {
            policy.with_tool_requirement(
                    tool.definition().name(),
                    permission_mode_for_plugin(tool.definition().required_permission()));
        }

        if (allowed_csv != null && !allowed_csv.isBlank()) {
            // Whitelist: deny every MVP tool not in the CSV. Plugin tools are always
            // permitted because they're declared via settings.json (the tighter,
            // explicit configuration boundary).
            Set<String> allowed = parse_csv(allowed_csv);
            List<String> deny_rules = new ArrayList<>();
            for (ToolSpec spec : MvpToolSpecs.mvp_tool_specs()) {
                if (!allowed.contains(spec.name())) {
                    deny_rules.add(spec.name());
                }
            }
            policy.with_permission_rules(new org.jclaude.runtime.permissions.RuntimePermissionRuleConfig(
                    Collections.emptyList(), deny_rules, Collections.emptyList()));
        }
        return policy;
    }

    /**
     * Phase 4 wave 2: optionally constructs a MCP bridge adapter when the runtime exposes one. The
     * {@code JCLAUDE_MCP_BRIDGE_ENABLED} env var gates the bridge; full settings.json integration is
     * Phase 4 wave 3. Returning {@link Optional#empty()} keeps the dispatcher on its Phase 3 stubs.
     */
    private static Optional<McpToolBridgeAdapter> build_mcp_bridge_adapter() {
        String enabled = System.getenv("JCLAUDE_MCP_BRIDGE_ENABLED");
        if (enabled == null || enabled.isBlank() || !enabled.equalsIgnoreCase("true")) {
            return Optional.empty();
        }
        return Optional.of(new McpToolBridgeAdapter(new McpToolBridge()));
    }

    /**
     * Phase 4 wave 2: optionally constructs a worker registry. The {@code JCLAUDE_WORKER_REGISTRY_ENABLED}
     * env var gates registration; without it, the dispatcher continues to return the Phase 3
     * worker-tool stubs.
     */
    private static Optional<WorkerRegistry> build_worker_registry() {
        String enabled = System.getenv("JCLAUDE_WORKER_REGISTRY_ENABLED");
        if (enabled == null || enabled.isBlank() || !enabled.equalsIgnoreCase("true")) {
            return Optional.empty();
        }
        return Optional.of(new WorkerRegistry());
    }

    /**
     * Phase 4 wave 2: returns an empty per-language LSP client map by default. CLI runs that need
     * live LSP must populate this map before calling the dispatcher constructor; full config-driven
     * spawning is Phase 4 wave 3.
     */
    private static Optional<Map<String, LspClient>> build_lsp_clients_by_language() {
        return Optional.empty();
    }

    private static PermissionMode permission_mode_for_plugin(String required_permission) {
        if (required_permission == null) {
            return PermissionMode.READ_ONLY;
        }
        return switch (required_permission) {
            case "workspace-write" -> PermissionMode.WORKSPACE_WRITE;
            case "danger-full-access" -> PermissionMode.DANGER_FULL_ACCESS;
            default -> PermissionMode.READ_ONLY;
        };
    }

    private static Set<String> parse_csv(String csv) {
        Set<String> out = new LinkedHashSet<>();
        for (String chunk : csv.split(",")) {
            String trimmed = chunk.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    static String join_prompt_args(String dash_p, List<String> positional) {
        if (dash_p != null && !dash_p.isBlank()) {
            return dash_p;
        }
        if (positional == null || positional.isEmpty()) {
            return null;
        }
        return String.join(" ", positional);
    }

    @SuppressWarnings("unused")
    private static String env_or_default(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    @SuppressWarnings("unused")
    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unused")
    private static List<String> split_words(String s) {
        if (s == null) {
            return List.of();
        }
        return Arrays.asList(s.split("\\s+"));
    }
}
