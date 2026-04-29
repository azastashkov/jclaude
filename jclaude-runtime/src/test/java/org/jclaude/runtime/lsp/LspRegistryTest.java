package org.jclaude.runtime.lsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LspRegistryTest {

    @Test
    void registers_and_retrieves_server() {
        LspRegistry registry = new LspRegistry();
        registry.register("rust", LspServerStatus.CONNECTED, "/workspace", List.of("hover", "completion"));

        LspServerState server = registry.get("rust").orElseThrow();
        assertThat(server.language()).isEqualTo("rust");
        assertThat(server.status()).isEqualTo(LspServerStatus.CONNECTED);
        assertThat(server.capabilities()).hasSize(2);
    }

    @Test
    void finds_server_by_file_extension() {
        LspRegistry registry = new LspRegistry();
        registry.register("rust", LspServerStatus.CONNECTED, null, List.of());
        registry.register("typescript", LspServerStatus.CONNECTED, null, List.of());

        LspServerState rs_server = registry.find_server_for_path("src/main.rs").orElseThrow();
        assertThat(rs_server.language()).isEqualTo("rust");

        LspServerState ts_server = registry.find_server_for_path("src/index.ts").orElseThrow();
        assertThat(ts_server.language()).isEqualTo("typescript");

        assertThat(registry.find_server_for_path("data.csv")).isEmpty();
    }

    @Test
    void manages_diagnostics() {
        LspRegistry registry = new LspRegistry();
        registry.register("rust", LspServerStatus.CONNECTED, null, List.of());

        registry.add_diagnostics(
                "rust", List.of(new LspDiagnostic("src/main.rs", 10, 5, "error", "mismatched types", "rust-analyzer")));

        List<LspDiagnostic> diags = registry.get_diagnostics("src/main.rs");
        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).message()).isEqualTo("mismatched types");

        registry.clear_diagnostics("rust");
        assertThat(registry.get_diagnostics("src/main.rs")).isEmpty();
    }

    @Test
    void dispatches_diagnostics_action() {
        LspRegistry registry = new LspRegistry();
        registry.register("rust", LspServerStatus.CONNECTED, null, List.of());
        registry.add_diagnostics(
                "rust", List.of(new LspDiagnostic("src/lib.rs", 1, 0, "warning", "unused import", null)));

        JsonNode result = registry.dispatch("diagnostics", "src/lib.rs", null, null, null);
        assertThat(result.get("count").asInt()).isEqualTo(1);
    }

    @Test
    void dispatches_hover_action() {
        LspRegistry registry = new LspRegistry();
        registry.register("rust", LspServerStatus.CONNECTED, null, List.of());

        JsonNode result = registry.dispatch("hover", "src/main.rs", 10, 5, null);
        assertThat(result.get("action").asText()).isEqualTo("hover");
        assertThat(result.get("language").asText()).isEqualTo("rust");
    }

    @Test
    void rejects_action_on_disconnected_server() {
        LspRegistry registry = new LspRegistry();
        registry.register("rust", LspServerStatus.DISCONNECTED, null, List.of());

        assertThatThrownBy(() -> registry.dispatch("hover", "src/main.rs", 1, 0, null))
                .isInstanceOf(LspRegistryException.class);
    }

    @Test
    void rejects_unknown_action() {
        LspRegistry registry = new LspRegistry();
        assertThatThrownBy(() -> registry.dispatch("unknown_action", "file.rs", null, null, null))
                .isInstanceOf(LspRegistryException.class);
    }

    @Test
    void disconnects_server() {
        LspRegistry registry = new LspRegistry();
        registry.register("rust", LspServerStatus.CONNECTED, null, List.of());
        assertThat(registry.len()).isEqualTo(1);

        Optional<LspServerState> removed = registry.disconnect("rust");
        assertThat(removed).isPresent();
        assertThat(registry.is_empty()).isTrue();
    }

    @Test
    void lsp_action_from_str_all_aliases() {
        List<Map.Entry<String, Optional<LspAction>>> cases = List.of(
                Map.entry("diagnostics", Optional.of(LspAction.DIAGNOSTICS)),
                Map.entry("hover", Optional.of(LspAction.HOVER)),
                Map.entry("definition", Optional.of(LspAction.DEFINITION)),
                Map.entry("goto_definition", Optional.of(LspAction.DEFINITION)),
                Map.entry("references", Optional.of(LspAction.REFERENCES)),
                Map.entry("find_references", Optional.of(LspAction.REFERENCES)),
                Map.entry("completion", Optional.of(LspAction.COMPLETION)),
                Map.entry("completions", Optional.of(LspAction.COMPLETION)),
                Map.entry("symbols", Optional.of(LspAction.SYMBOLS)),
                Map.entry("document_symbols", Optional.of(LspAction.SYMBOLS)),
                Map.entry("format", Optional.of(LspAction.FORMAT)),
                Map.entry("formatting", Optional.of(LspAction.FORMAT)),
                new AbstractMap.SimpleImmutableEntry<>("unknown", Optional.<LspAction>empty()));

        for (Map.Entry<String, Optional<LspAction>> entry : cases) {
            assertThat(LspAction.from_str(entry.getKey()))
                    .as("unexpected action resolution for %s", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    void lsp_server_status_display_all_variants() {
        List<LspServerStatus> statuses = List.of(
                LspServerStatus.CONNECTED,
                LspServerStatus.DISCONNECTED,
                LspServerStatus.STARTING,
                LspServerStatus.ERROR);
        List<String> expected = List.of("connected", "disconnected", "starting", "error");
        for (int i = 0; i < statuses.size(); i++) {
            assertThat(statuses.get(i).display()).isEqualTo(expected.get(i));
        }
    }

    @Test
    void dispatch_diagnostics_without_path_aggregates() {
        LspRegistry registry = new LspRegistry();
        registry.register("rust", LspServerStatus.CONNECTED, null, List.of());
        registry.register("python", LspServerStatus.CONNECTED, null, List.of());
        registry.add_diagnostics(
                "rust", List.of(new LspDiagnostic("src/lib.rs", 1, 0, "warning", "unused import", "rust-analyzer")));
        registry.add_diagnostics(
                "python", List.of(new LspDiagnostic("script.py", 2, 4, "error", "undefined name", "pyright")));

        JsonNode result = registry.dispatch("diagnostics", null, null, null, null);

        assertThat(result.get("action").asText()).isEqualTo("diagnostics");
        assertThat(result.get("count").asInt()).isEqualTo(2);
        assertThat(result.get("diagnostics").size()).isEqualTo(2);
    }

    @Test
    void dispatch_non_diagnostics_requires_path() {
        LspRegistry registry = new LspRegistry();

        assertThatThrownBy(() -> registry.dispatch("hover", null, 1, 0, null))
                .isInstanceOf(LspRegistryException.class)
                .hasMessage("path is required for this LSP action");
    }

    @Test
    void dispatch_no_server_for_path_errors() {
        LspRegistry registry = new LspRegistry();

        assertThatThrownBy(() -> registry.dispatch("hover", "notes.md", 1, 0, null))
                .isInstanceOf(LspRegistryException.class)
                .hasMessageContaining("no LSP server available for path: notes.md");
    }

    @Test
    void dispatch_disconnected_server_error_payload() {
        LspRegistry registry = new LspRegistry();
        registry.register("typescript", LspServerStatus.DISCONNECTED, null, List.of());

        assertThatThrownBy(() -> registry.dispatch("hover", "src/index.ts", 3, 2, null))
                .isInstanceOf(LspRegistryException.class)
                .hasMessageContaining("typescript")
                .hasMessageContaining("disconnected");
    }

    @Test
    void find_server_for_all_extensions() {
        LspRegistry registry = new LspRegistry();
        for (String language :
                List.of("rust", "typescript", "javascript", "python", "go", "java", "c", "cpp", "ruby", "lua")) {
            registry.register(language, LspServerStatus.CONNECTED, null, List.of());
        }

        List<Map.Entry<String, String>> cases = List.of(
                Map.entry("src/main.rs", "rust"),
                Map.entry("src/index.ts", "typescript"),
                Map.entry("src/view.tsx", "typescript"),
                Map.entry("src/app.js", "javascript"),
                Map.entry("src/app.jsx", "javascript"),
                Map.entry("script.py", "python"),
                Map.entry("main.go", "go"),
                Map.entry("Main.java", "java"),
                Map.entry("native.c", "c"),
                Map.entry("native.h", "c"),
                Map.entry("native.cpp", "cpp"),
                Map.entry("native.hpp", "cpp"),
                Map.entry("native.cc", "cpp"),
                Map.entry("script.rb", "ruby"),
                Map.entry("script.lua", "lua"));

        for (Map.Entry<String, String> entry : cases) {
            String path = entry.getKey();
            String expected = entry.getValue();
            assertThat(registry.find_server_for_path(path).map(LspServerState::language))
                    .as("unexpected mapping for %s", path)
                    .contains(expected);
        }
    }

    @Test
    void find_server_for_path_no_extension() {
        LspRegistry registry = new LspRegistry();
        registry.register("rust", LspServerStatus.CONNECTED, null, List.of());

        assertThat(registry.find_server_for_path("Makefile")).isEmpty();
    }

    @Test
    void list_servers_with_multiple() {
        LspRegistry registry = new LspRegistry();
        registry.register("rust", LspServerStatus.CONNECTED, null, List.of());
        registry.register("typescript", LspServerStatus.STARTING, null, List.of());
        registry.register("python", LspServerStatus.ERROR, null, List.of());

        List<LspServerState> servers = registry.list_servers();

        assertThat(servers).hasSize(3);
        assertThat(servers).anyMatch(server -> server.language().equals("rust"));
        assertThat(servers).anyMatch(server -> server.language().equals("typescript"));
        assertThat(servers).anyMatch(server -> server.language().equals("python"));
    }

    @Test
    void get_missing_server_returns_none() {
        LspRegistry registry = new LspRegistry();

        assertThat(registry.get("missing")).isEmpty();
    }

    @Test
    void add_diagnostics_missing_language_errors() {
        LspRegistry registry = new LspRegistry();

        assertThatThrownBy(() -> registry.add_diagnostics("missing", List.of()))
                .isInstanceOf(LspRegistryException.class)
                .hasMessageContaining("LSP server not found for language: missing");
    }

    @Test
    void get_diagnostics_across_servers() {
        LspRegistry registry = new LspRegistry();
        String shared_path = "shared/file.txt";
        registry.register("rust", LspServerStatus.CONNECTED, null, List.of());
        registry.register("python", LspServerStatus.CONNECTED, null, List.of());
        registry.add_diagnostics("rust", List.of(new LspDiagnostic(shared_path, 4, 1, "warning", "warn", null)));
        registry.add_diagnostics("python", List.of(new LspDiagnostic(shared_path, 8, 3, "error", "err", null)));

        List<LspDiagnostic> diagnostics = registry.get_diagnostics(shared_path);

        assertThat(diagnostics).hasSize(2);
        assertThat(diagnostics).anyMatch(diagnostic -> diagnostic.message().equals("warn"));
        assertThat(diagnostics).anyMatch(diagnostic -> diagnostic.message().equals("err"));
    }

    @Test
    void clear_diagnostics_missing_language_errors() {
        LspRegistry registry = new LspRegistry();

        assertThatThrownBy(() -> registry.clear_diagnostics("missing"))
                .isInstanceOf(LspRegistryException.class)
                .hasMessageContaining("LSP server not found for language: missing");
    }
}
