package org.jclaude.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Test isolation utility — port of {@code test_isolation.rs}. Acquires a process-wide lock and sets
 * up a temporary {@code HOME} directory (with {@code .claude/plugins/installed} skeleton) so plugin
 * tests don't bleed against the real user's config.
 *
 * <p>Java cannot mutate {@link System#getenv()} after JVM start, so this helper records the temp
 * home but cannot truly reassign {@code HOME}/{@code XDG_CONFIG_HOME}/{@code XDG_DATA_HOME} env
 * vars; tests should pass {@link #temp_home()} explicitly into APIs that take a config home (e.g.
 * {@link PluginManagerConfig}).
 */
public final class EnvLock implements AutoCloseable {

    private static final AtomicLong TEST_COUNTER = new AtomicLong(0);
    private static final ReentrantLock ENV_LOCK = new ReentrantLock();

    private final Path temp_home;

    private EnvLock(Path temp_home) {
        this.temp_home = temp_home;
    }

    public static EnvLock lock() {
        ENV_LOCK.lock();
        long count = TEST_COUNTER.getAndIncrement();
        Path temp_home = Paths.get(System.getProperty("java.io.tmpdir")).resolve("plugin-test-" + count);
        try {
            Files.createDirectories(temp_home);
            Files.createDirectories(temp_home.resolve(".claude/plugins/installed"));
            Files.createDirectories(temp_home.resolve(".config"));
        } catch (IOException ignored) {
            // best-effort
        }
        return new EnvLock(temp_home);
    }

    public Path temp_home() {
        return temp_home;
    }

    @Override
    public void close() {
        try {
            delete_recursively(temp_home);
        } catch (IOException ignored) {
            // best-effort cleanup
        } finally {
            if (ENV_LOCK.isHeldByCurrentThread()) {
                ENV_LOCK.unlock();
            }
        }
    }

    private static void delete_recursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
}
