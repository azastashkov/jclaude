package org.jclaude.runtime.lsp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Diagnostic surfaced by an LSP server for a particular file location. */
public record LspDiagnostic(
        @JsonProperty("path") String path,
        @JsonProperty("line") int line,
        @JsonProperty("character") int character,
        @JsonProperty("severity") String severity,
        @JsonProperty("message") String message,
        @JsonProperty("source") String source) {

    @JsonCreator
    public LspDiagnostic {}
}
