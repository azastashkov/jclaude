package org.jclaude.runtime.lsp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/** Supported LSP actions dispatched through {@link LspRegistry#dispatch}. */
public enum LspAction {
    DIAGNOSTICS("diagnostics"),
    HOVER("hover"),
    DEFINITION("definition"),
    REFERENCES("references"),
    COMPLETION("completion"),
    SYMBOLS("symbols"),
    FORMAT("format");

    private final String wire;

    LspAction(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static LspAction from_wire(String value) {
        return from_str(value).orElseThrow(() -> new IllegalArgumentException("unsupported lsp action: " + value));
    }

    /** Mirrors {@code LspAction::from_str} including alias handling. */
    public static Optional<LspAction> from_str(String value) {
        return switch (value) {
            case "diagnostics" -> Optional.of(DIAGNOSTICS);
            case "hover" -> Optional.of(HOVER);
            case "definition", "goto_definition" -> Optional.of(DEFINITION);
            case "references", "find_references" -> Optional.of(REFERENCES);
            case "completion", "completions" -> Optional.of(COMPLETION);
            case "symbols", "document_symbols" -> Optional.of(SYMBOLS);
            case "format", "formatting" -> Optional.of(FORMAT);
            default -> Optional.empty();
        };
    }
}
