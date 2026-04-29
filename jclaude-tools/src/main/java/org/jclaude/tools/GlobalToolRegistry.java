package org.jclaude.tools;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide holder for the active set of {@link ToolSpec} instances exposed to the model in the
 * {@code tools} field of an Anthropic message request. Phase 1 ships with the 13-tool MVP slice
 * preloaded; later phases can swap in larger registries (deferred tools, plugin tools, MCP tools).
 *
 * <p>This is intentionally a singleton-style container rather than a true singleton — the runtime
 * can always construct private registries via the constructors when isolation is needed. Use
 * {@link #global()} for the well-known shared instance.
 */
public final class GlobalToolRegistry {

    private static final GlobalToolRegistry GLOBAL = new GlobalToolRegistry(MvpToolSpecs.mvp_tool_specs());

    private final AtomicReference<List<ToolSpec>> specs;

    public GlobalToolRegistry(List<ToolSpec> specs) {
        if (specs == null) {
            throw new IllegalArgumentException("specs must not be null");
        }
        this.specs = new AtomicReference<>(List.copyOf(specs));
    }

    /** Returns the shared registry preloaded with the MVP tool specs. */
    public static GlobalToolRegistry global() {
        return GLOBAL;
    }

    /** Replaces the active spec list. Subsequent calls to {@link #specs()} see the new value. */
    public void set_specs(List<ToolSpec> next) {
        if (next == null) {
            throw new IllegalArgumentException("next must not be null");
        }
        specs.set(List.copyOf(next));
    }

    /** Returns an immutable snapshot of the active tool specs. */
    public List<ToolSpec> specs() {
        return specs.get();
    }

    /** Looks up a spec by exact name. */
    public java.util.Optional<ToolSpec> find(String name) {
        if (name == null) {
            return java.util.Optional.empty();
        }
        for (ToolSpec spec : specs.get()) {
            if (spec.name().equals(name)) {
                return java.util.Optional.of(spec);
            }
        }
        return java.util.Optional.empty();
    }
}
