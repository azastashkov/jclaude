package org.jclaude.runtime.lsp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A location within a source file as reported by an LSP server. */
public record LspLocation(
        @JsonProperty("path") String path,
        @JsonProperty("line") int line,
        @JsonProperty("character") int character,
        @JsonProperty("end_line") Integer end_line,
        @JsonProperty("end_character") Integer end_character,
        @JsonProperty("preview") String preview) {

    @JsonCreator
    public LspLocation {}
}
