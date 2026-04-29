package org.jclaude.runtime.bash.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommandSemanticsTest {

    @Test
    void classifies_read_only_commands() {
        assertThat(CommandSemantics.classify("ls -la")).isEqualTo(CommandIntent.READ_ONLY);
        assertThat(CommandSemantics.classify("cat file.txt")).isEqualTo(CommandIntent.READ_ONLY);
        assertThat(CommandSemantics.classify("grep -r pattern .")).isEqualTo(CommandIntent.READ_ONLY);
        assertThat(CommandSemantics.classify("find . -name '*.rs'")).isEqualTo(CommandIntent.READ_ONLY);
    }

    @Test
    void classifies_write_commands() {
        assertThat(CommandSemantics.classify("cp a.txt b.txt")).isEqualTo(CommandIntent.WRITE);
        assertThat(CommandSemantics.classify("mv old.txt new.txt")).isEqualTo(CommandIntent.WRITE);
        assertThat(CommandSemantics.classify("mkdir -p /tmp/dir")).isEqualTo(CommandIntent.WRITE);
    }

    @Test
    void classifies_destructive_commands() {
        assertThat(CommandSemantics.classify("rm -rf /tmp/x")).isEqualTo(CommandIntent.DESTRUCTIVE);
        assertThat(CommandSemantics.classify("shred /dev/sda")).isEqualTo(CommandIntent.DESTRUCTIVE);
    }

    @Test
    void classifies_network_commands() {
        assertThat(CommandSemantics.classify("curl https://example.com")).isEqualTo(CommandIntent.NETWORK);
        assertThat(CommandSemantics.classify("wget file.zip")).isEqualTo(CommandIntent.NETWORK);
    }

    @Test
    void classifies_sed_inplace_as_write() {
        assertThat(CommandSemantics.classify("sed -i 's/old/new/' file.txt")).isEqualTo(CommandIntent.WRITE);
    }

    @Test
    void classifies_sed_stdout_as_read_only() {
        assertThat(CommandSemantics.classify("sed 's/old/new/' file.txt")).isEqualTo(CommandIntent.READ_ONLY);
    }

    @Test
    void classifies_git_status_as_read_only() {
        assertThat(CommandSemantics.classify("git status")).isEqualTo(CommandIntent.READ_ONLY);
        assertThat(CommandSemantics.classify("git log --oneline")).isEqualTo(CommandIntent.READ_ONLY);
    }

    @Test
    void classifies_git_push_as_write() {
        assertThat(CommandSemantics.classify("git push origin main")).isEqualTo(CommandIntent.WRITE);
    }
}
