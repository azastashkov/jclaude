package org.jclaude.plugins;

import java.nio.file.Path;

/** Origin used when installing or re-materializing a plugin (local path or git URL). */
public sealed interface PluginInstallSource {

    record LocalPath(Path path) implements PluginInstallSource {}

    record GitUrl(String url) implements PluginInstallSource {}

    default String describe() {
        return switch (this) {
            case LocalPath l -> l.path().toString();
            case GitUrl g -> g.url();
        };
    }
}
