package org.jclaude.runtime.lsp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A document symbol reported by an LSP server. */
public record LspSymbol(
        @JsonProperty("name") String name,
        @JsonProperty("kind") String kind,
        @JsonProperty("path") String path,
        @JsonProperty("line") int line,
        @JsonProperty("character") int character) {

    @JsonCreator
    public LspSymbol {}
}
