package org.jclaude.runtime.prompt;

import java.nio.file.Path;

/** Contents of an instruction file included in prompt construction. */
public record ContextFile(Path path, String content) {}
