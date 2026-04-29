package org.jclaude.commands;

/** Thrown by {@link SkillsHandler#resolve_skill_invocation(java.nio.file.Path, String)} when no skill matches. */
public final class SkillResolutionException extends RuntimeException {

    public SkillResolutionException(String message) {
        super(message);
    }
}
