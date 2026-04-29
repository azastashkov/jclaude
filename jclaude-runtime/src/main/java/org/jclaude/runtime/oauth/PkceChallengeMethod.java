package org.jclaude.runtime.oauth;

/** Challenge algorithm enum. */
public enum PkceChallengeMethod {
    S256("S256");

    private final String wire;

    PkceChallengeMethod(String wire) {
        this.wire = wire;
    }

    public String as_str() {
        return wire;
    }
}
