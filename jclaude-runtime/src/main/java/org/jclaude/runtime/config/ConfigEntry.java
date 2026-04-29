package org.jclaude.runtime.config;

import java.nio.file.Path;

/** A discovered config file and the scope it contributes to. */
public record ConfigEntry(ConfigSource source, Path path) {}
