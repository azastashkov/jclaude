package org.jclaude.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class EnvLockTest {

    @Test
    void test_env_lock_creates_isolated_home() {
        try (EnvLock lock = EnvLock.lock()) {
            assertThat(lock.temp_home().toString()).contains("plugin-test-");
            assertThat(Files.isDirectory(lock.temp_home())).isTrue();
        }
    }

    @Test
    void test_env_lock_creates_plugin_directories() {
        try (EnvLock lock = EnvLock.lock()) {
            var plugins_dir = lock.temp_home().resolve(".claude/plugins/installed");
            assertThat(Files.exists(plugins_dir)).isTrue();
        }
    }

    @Test
    void env_guard_recovers_after_poisoning() throws Exception {
        // Java's ReentrantLock doesn't poison on panic the way a Rust Mutex does. Spawn a thread
        // that throws while holding the lock and verify the next caller still acquires it cleanly.
        Thread poisoned = new Thread(() -> {
            try (EnvLock ignored = EnvLock.lock()) {
                throw new RuntimeException("poison env lock");
            }
        });
        poisoned.start();
        poisoned.join();

        try (EnvLock lock = EnvLock.lock()) {
            assertThat(Files.isDirectory(lock.temp_home())).isTrue();
        }
    }
}
