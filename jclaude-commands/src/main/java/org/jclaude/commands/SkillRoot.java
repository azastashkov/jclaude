package org.jclaude.commands;

import java.nio.file.Path;

/**
 * One directory tree to search when discovering skills. Mirrors Rust
 * {@code SkillRoot}.
 */
public record SkillRoot(DefinitionSource source, Path path, SkillOrigin origin) {}
