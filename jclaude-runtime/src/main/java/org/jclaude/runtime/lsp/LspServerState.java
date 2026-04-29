package org.jclaude.runtime.lsp;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** State recorded for a registered LSP server. */
public final class LspServerState {

    private final String language;
    private final LspServerStatus status;
    private final String root_path;
    private final List<String> capabilities;
    private final List<LspDiagnostic> diagnostics;

    LspServerState(
            String language,
            LspServerStatus status,
            String root_path,
            List<String> capabilities,
            List<LspDiagnostic> diagnostics) {
        this.language = language;
        this.status = status;
        this.root_path = root_path;
        this.capabilities = new ArrayList<>(capabilities);
        this.diagnostics = new ArrayList<>(diagnostics);
    }

    private LspServerState(LspServerState other) {
        this(other.language, other.status, other.root_path, other.capabilities, other.diagnostics);
    }

    public LspServerState snapshot() {
        return new LspServerState(this);
    }

    @JsonProperty("language")
    public String language() {
        return language;
    }

    @JsonProperty("status")
    public LspServerStatus status() {
        return status;
    }

    @JsonProperty("root_path")
    public Optional<String> root_path() {
        return Optional.ofNullable(root_path);
    }

    @JsonProperty("capabilities")
    public List<String> capabilities() {
        return List.copyOf(capabilities);
    }

    @JsonProperty("diagnostics")
    public List<LspDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    void add_diagnostics_internal(List<LspDiagnostic> next) {
        this.diagnostics.addAll(next);
    }

    void clear_diagnostics_internal() {
        this.diagnostics.clear();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LspServerState other)) {
            return false;
        }
        return language.equals(other.language)
                && status == other.status
                && Objects.equals(root_path, other.root_path)
                && capabilities.equals(other.capabilities)
                && diagnostics.equals(other.diagnostics);
    }

    @Override
    public int hashCode() {
        return Objects.hash(language, status, root_path, capabilities, diagnostics);
    }
}
