package org.jclaude.runtime.conversation;

import java.util.TreeMap;
import java.util.function.Function;

/** Simple in-memory tool executor for tests and lightweight integrations. */
public final class StaticToolExecutor implements ToolExecutor {

    private final TreeMap<String, Function<String, String>> handlers;

    public StaticToolExecutor() {
        this.handlers = new TreeMap<>();
    }

    public static StaticToolExecutor create() {
        return new StaticToolExecutor();
    }

    /**
     * Registers {@code handler} as the implementation for {@code tool_name}. The
     * handler receives the raw JSON input string and may either return its output
     * or throw a {@link ToolError} to surface a failure.
     */
    public StaticToolExecutor register(String tool_name, Function<String, String> handler) {
        this.handlers.put(tool_name, handler);
        return this;
    }

    @Override
    public String execute(String tool_name, String input) {
        Function<String, String> handler = handlers.get(tool_name);
        if (handler == null) {
            throw new ToolError("unknown tool: " + tool_name);
        }
        return handler.apply(input);
    }
}
