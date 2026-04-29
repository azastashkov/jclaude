package org.jclaude.runtime.files;

/**
 * Text payload returned by file-reading operations.
 */
public record TextFilePayload(String file_path, String content, int num_lines, int start_line, int total_lines) {}
