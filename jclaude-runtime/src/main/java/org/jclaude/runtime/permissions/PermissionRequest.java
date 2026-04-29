package org.jclaude.runtime.permissions;

import java.util.Optional;

/** Full authorization request presented to a permission prompt. */
public record PermissionRequest(
        String tool_name,
        String input,
        PermissionMode current_mode,
        PermissionMode required_mode,
        Optional<String> reason) {}
