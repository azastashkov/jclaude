package org.jclaude.runtime.permissions;

sealed interface PermissionRuleMatcher {
    record Any() implements PermissionRuleMatcher {}

    record Exact(String expected) implements PermissionRuleMatcher {}

    record Prefix(String prefix) implements PermissionRuleMatcher {}
}
