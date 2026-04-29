package org.jclaude.runtime.config;

import java.util.List;

/** Raw permission rule lists. */
public record RuntimePermissionRuleConfig(List<String> allow, List<String> deny, List<String> ask) {

    public RuntimePermissionRuleConfig {
        allow = List.copyOf(allow);
        deny = List.copyOf(deny);
        ask = List.copyOf(ask);
    }

    public static RuntimePermissionRuleConfig empty() {
        return new RuntimePermissionRuleConfig(List.of(), List.of(), List.of());
    }
}
