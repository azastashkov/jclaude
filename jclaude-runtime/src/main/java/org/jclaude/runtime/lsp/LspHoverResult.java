package org.jclaude.runtime.lsp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Hover content returned by an LSP server. */
public record LspHoverResult(@JsonProperty("content") String content, @JsonProperty("language") String language) {

    @JsonCreator
    public LspHoverResult {}
}
