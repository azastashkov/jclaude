package org.jclaude.runtime.mcp.jsonrpc;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.Objects;

/**
 * JSON-RPC 2.0 request/response id. Mirrors the untagged Rust {@code JsonRpcId} enum which
 * accepts a number, a string, or null. We model it as a Java sealed interface with three
 * concrete variants so callers can pattern-match the runtime shape; Jackson serialises each
 * variant transparently to its scalar JSON form.
 */
@JsonDeserialize(using = JsonRpcId.Deserializer.class)
public sealed interface JsonRpcId {

    /** Numeric id. */
    record Number(long value) implements JsonRpcId {

        @JsonValue
        public long jsonValue() {
            return value;
        }
    }

    /** String id. */
    record Text(String value) implements JsonRpcId {

        public Text {
            Objects.requireNonNull(value, "value");
        }

        @JsonValue
        public String jsonValue() {
            return value;
        }
    }

    /** Null id (used for transport-level parse errors per JSON-RPC 2.0 §4.2). */
    record Null() implements JsonRpcId {

        public static final Null INSTANCE = new Null();

        @JsonValue
        public Object jsonValue() {
            return null;
        }
    }

    static JsonRpcId of(long value) {
        return new Number(value);
    }

    static JsonRpcId of(String value) {
        return new Text(value);
    }

    static JsonRpcId nullId() {
        return Null.INSTANCE;
    }

    /** Custom Jackson deserialiser that branches on the wire token. */
    final class Deserializer extends StdDeserializer<JsonRpcId> {

        private static final long serialVersionUID = 1L;

        public Deserializer() {
            super(JsonRpcId.class);
        }

        @Override
        public JsonRpcId deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.currentToken();
            return switch (token) {
                case VALUE_NUMBER_INT -> new Number(p.getLongValue());
                case VALUE_NUMBER_FLOAT -> new Number((long) p.getDoubleValue());
                case VALUE_STRING -> new Text(p.getValueAsString());
                case VALUE_NULL -> Null.INSTANCE;
                default -> throw ctxt.wrongTokenException(p, JsonRpcId.class, JsonToken.VALUE_STRING, "JSON-RPC id");
            };
        }
    }
}
