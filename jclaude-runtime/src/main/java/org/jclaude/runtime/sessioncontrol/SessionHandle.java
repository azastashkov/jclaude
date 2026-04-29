package org.jclaude.runtime.sessioncontrol;

import java.nio.file.Path;

/** Handle to a session file on disk. */
public record SessionHandle(String id, Path path) {}
