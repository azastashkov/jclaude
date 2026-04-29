package org.jclaude.runtime.git;

/** Levels of greenness mirrored from the Rust {@code GreenLevel} enum. */
public enum GreenLevel {
    TARGETED_TESTS("targeted_tests"),
    PACKAGE("package"),
    WORKSPACE("workspace"),
    MERGE_READY("merge_ready");

    private final String wire;

    GreenLevel(String wire) {
        this.wire = wire;
    }

    public String as_str() {
        return wire;
    }

    @Override
    public String toString() {
        return wire;
    }
}
