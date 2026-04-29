package org.jclaude.runtime.permissions;

import java.util.List;

/** Raw permission rule lists grouped by allow, deny, and ask behavior. */
public record RuntimePermissionRuleConfig(List<String> allow, List<String> deny, List<String> ask) {
    public RuntimePermissionRuleConfig {
        allow = List.copyOf(allow);
        deny = List.copyOf(deny);
        ask = List.copyOf(ask);
    }

    public static RuntimePermissionRuleConfig defaultConfig() {
        return new RuntimePermissionRuleConfig(List.of(), List.of(), List.of());
    }
}
