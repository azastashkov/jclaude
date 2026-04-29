package org.jclaude.runtime.files;

import java.util.List;

/**
 * Structured patch hunk emitted by write and edit operations.
 */
public record StructuredPatchHunk(int old_start, int old_lines, int new_start, int new_lines, List<String> lines) {}
