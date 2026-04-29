package org.jclaude.runtime.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory LSP server registry. Mirrors the data model and dispatch logic of the Rust
 * {@code LspRegistry} — Phase 4 will plug actual LSP transport into the dispatch path.
 */
public final class LspRegistry {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Object lock = new Object();
    private final Map<String, LspServerState> servers = new HashMap<>();

    public LspRegistry() {}

    public void register(String language, LspServerStatus status, String root_path, List<String> capabilities) {
        synchronized (lock) {
            servers.put(language, new LspServerState(language, status, root_path, capabilities, List.of()));
        }
    }

    public Optional<LspServerState> get(String language) {
        synchronized (lock) {
            LspServerState state = servers.get(language);
            return state == null ? Optional.empty() : Optional.of(state.snapshot());
        }
    }

    /** Resolves the LSP server most appropriate for {@code path} based on file extension. */
    public Optional<LspServerState> find_server_for_path(String path) {
        String ext = extension(path);
        String language =
                switch (ext) {
                    case "rs" -> "rust";
                    case "ts", "tsx" -> "typescript";
                    case "js", "jsx" -> "javascript";
                    case "py" -> "python";
                    case "go" -> "go";
                    case "java" -> "java";
                    case "c", "h" -> "c";
                    case "cpp", "hpp", "cc" -> "cpp";
                    case "rb" -> "ruby";
                    case "lua" -> "lua";
                    default -> null;
                };
        if (language == null) {
            return Optional.empty();
        }
        return get(language);
    }

    public List<LspServerState> list_servers() {
        synchronized (lock) {
            List<LspServerState> result = new ArrayList<>();
            for (LspServerState state : servers.values()) {
                result.add(state.snapshot());
            }
            return result;
        }
    }

    public void add_diagnostics(String language, List<LspDiagnostic> diagnostics) {
        synchronized (lock) {
            LspServerState state = servers.get(language);
            if (state == null) {
                throw new LspRegistryException("LSP server not found for language: " + language);
            }
            state.add_diagnostics_internal(diagnostics);
        }
    }

    public List<LspDiagnostic> get_diagnostics(String path) {
        synchronized (lock) {
            List<LspDiagnostic> result = new ArrayList<>();
            for (LspServerState state : servers.values()) {
                for (LspDiagnostic diagnostic : state.diagnostics()) {
                    if (diagnostic.path().equals(path)) {
                        result.add(diagnostic);
                    }
                }
            }
            return result;
        }
    }

    public void clear_diagnostics(String language) {
        synchronized (lock) {
            LspServerState state = servers.get(language);
            if (state == null) {
                throw new LspRegistryException("LSP server not found for language: " + language);
            }
            state.clear_diagnostics_internal();
        }
    }

    public Optional<LspServerState> disconnect(String language) {
        synchronized (lock) {
            LspServerState removed = servers.remove(language);
            return removed == null ? Optional.empty() : Optional.of(removed);
        }
    }

    public int len() {
        synchronized (lock) {
            return servers.size();
        }
    }

    public boolean is_empty() {
        return len() == 0;
    }

    /** Dispatches an LSP action and returns the structured JSON response (Phase 1: state-only stub). */
    public JsonNode dispatch(String action, String path, Integer line, Integer character, String query) {
        Optional<LspAction> resolved = LspAction.from_str(action);
        if (resolved.isEmpty()) {
            throw new LspRegistryException("unknown LSP action: " + action);
        }
        LspAction lsp_action = resolved.get();

        if (lsp_action == LspAction.DIAGNOSTICS) {
            ObjectNode result = JSON.createObjectNode();
            result.put("action", "diagnostics");
            if (path != null) {
                result.put("path", path);
                List<LspDiagnostic> diags = get_diagnostics(path);
                result.set("diagnostics", to_json_array(diags));
                result.put("count", diags.size());
                return result;
            }
            List<LspDiagnostic> all_diags = collect_all_diagnostics();
            result.set("diagnostics", to_json_array(all_diags));
            result.put("count", all_diags.size());
            return result;
        }

        if (path == null) {
            throw new LspRegistryException("path is required for this LSP action");
        }
        Optional<LspServerState> server = find_server_for_path(path);
        if (server.isEmpty()) {
            throw new LspRegistryException("no LSP server available for path: " + path);
        }
        LspServerState state = server.get();
        if (state.status() != LspServerStatus.CONNECTED) {
            throw new LspRegistryException("LSP server for '" + state.language() + "' is not connected (status: "
                    + state.status().display() + ")");
        }

        ObjectNode response = JSON.createObjectNode();
        response.put("action", action);
        response.put("path", path);
        if (line != null) {
            response.put("line", line);
        } else {
            response.putNull("line");
        }
        if (character != null) {
            response.put("character", character);
        } else {
            response.putNull("character");
        }
        response.put("language", state.language());
        response.put("status", "dispatched");
        response.put("message", "LSP " + action + " dispatched to " + state.language() + " server");
        return response;
    }

    private List<LspDiagnostic> collect_all_diagnostics() {
        synchronized (lock) {
            List<LspDiagnostic> result = new ArrayList<>();
            for (LspServerState state : servers.values()) {
                result.addAll(state.diagnostics());
            }
            return result;
        }
    }

    private static ArrayNode to_json_array(List<LspDiagnostic> diagnostics) {
        ArrayNode array = JSON.createArrayNode();
        for (LspDiagnostic diagnostic : diagnostics) {
            array.add(JSON.valueToTree(diagnostic));
        }
        return array;
    }

    private static String extension(String path) {
        Path p = Path.of(path);
        Path file = p.getFileName();
        if (file == null) {
            return "";
        }
        String name = file.toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1);
    }
}
