package org.jclaude.runtime.lsp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A completion suggestion returned by an LSP server. */
public record LspCompletionItem(
        @JsonProperty("label") String label,
        @JsonProperty("kind") String kind,
        @JsonProperty("detail") String detail,
        @JsonProperty("insert_text") String insert_text) {

    @JsonCreator
    public LspCompletionItem {}
}
