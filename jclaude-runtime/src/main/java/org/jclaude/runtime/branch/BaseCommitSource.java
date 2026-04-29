package org.jclaude.runtime.branch;

/** Where the expected base commit originated from. */
public sealed interface BaseCommitSource {

    String value();

    record Flag(String value) implements BaseCommitSource {}

    record File(String value) implements BaseCommitSource {}
}
