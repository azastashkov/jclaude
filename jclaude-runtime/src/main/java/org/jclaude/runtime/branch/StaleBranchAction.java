package org.jclaude.runtime.branch;

/** Action returned from {@link StaleBranch#apply_policy}. */
public sealed interface StaleBranchAction {

    record Noop() implements StaleBranchAction {}

    record Warn(String message) implements StaleBranchAction {}

    record Block(String message) implements StaleBranchAction {}

    record Rebase() implements StaleBranchAction {}

    record MergeForward() implements StaleBranchAction {}
}
