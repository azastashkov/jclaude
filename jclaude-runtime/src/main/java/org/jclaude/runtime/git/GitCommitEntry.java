package org.jclaude.runtime.git;

/** A single git commit entry from the log. */
public record GitCommitEntry(String hash, String subject) {}
