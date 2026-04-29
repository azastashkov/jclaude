package org.jclaude.runtime.sandbox;

import java.util.List;

/** Detected container environment markers. */
public record ContainerEnvironment(boolean in_container, List<String> markers) {

    public ContainerEnvironment {
        markers = List.copyOf(markers);
    }
}
