package org.jclaude.runtime.branch;

/** Outcome of comparing the worktree HEAD against the expected base commit. */
public sealed interface BaseCommitState {

    record Matches() implements BaseCommitState {}

    record Diverged(String expected, String actual) implements BaseCommitState {}

    record NoExpectedBase() implements BaseCommitState {}

    record NotAGitRepo() implements BaseCommitState {}
}
