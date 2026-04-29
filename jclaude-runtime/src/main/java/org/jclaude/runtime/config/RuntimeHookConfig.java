package org.jclaude.runtime.config;

import java.util.List;

/** Hook command lists grouped by lifecycle stage. */
public record RuntimeHookConfig(
        List<String> pre_tool_use, List<String> post_tool_use, List<String> post_tool_use_failure) {

    public RuntimeHookConfig {
        pre_tool_use = List.copyOf(pre_tool_use);
        post_tool_use = List.copyOf(post_tool_use);
        post_tool_use_failure = List.copyOf(post_tool_use_failure);
    }

    public static RuntimeHookConfig empty() {
        return new RuntimeHookConfig(List.of(), List.of(), List.of());
    }
}
