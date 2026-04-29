package org.jclaude.plugins;

import java.util.ArrayList;
import java.util.List;

/** Hook command lists keyed by event name (PreToolUse / PostToolUse / PostToolUseFailure). */
public record PluginHooks(List<String> pre_tool_use, List<String> post_tool_use, List<String> post_tool_use_failure) {

    public PluginHooks {
        pre_tool_use = pre_tool_use == null ? List.of() : List.copyOf(pre_tool_use);
        post_tool_use = post_tool_use == null ? List.of() : List.copyOf(post_tool_use);
        post_tool_use_failure = post_tool_use_failure == null ? List.of() : List.copyOf(post_tool_use_failure);
    }

    public static PluginHooks empty() {
        return new PluginHooks(List.of(), List.of(), List.of());
    }

    public boolean is_empty() {
        return pre_tool_use.isEmpty() && post_tool_use.isEmpty() && post_tool_use_failure.isEmpty();
    }

    public PluginHooks merged_with(PluginHooks other) {
        if (other == null) {
            return this;
        }
        List<String> pre = new ArrayList<>(pre_tool_use);
        pre.addAll(other.pre_tool_use);
        List<String> post = new ArrayList<>(post_tool_use);
        post.addAll(other.post_tool_use);
        List<String> failure = new ArrayList<>(post_tool_use_failure);
        failure.addAll(other.post_tool_use_failure);
        return new PluginHooks(pre, post, failure);
    }
}
