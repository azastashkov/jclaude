package org.jclaude.runtime.branch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BranchLockTest {

    @Test
    void detects_same_branch_same_module_collisions() {
        List<BranchLockCollision> collisions = BranchLock.detect_branch_lock_collisions(List.of(
                new BranchLockIntent("lane-a", "feature/lock", "wt-a", List.of("runtime/mcp")),
                new BranchLockIntent("lane-b", "feature/lock", "wt-b", List.of("runtime/mcp"))));

        assertThat(collisions).hasSize(1);
        assertThat(collisions.get(0).branch()).isEqualTo("feature/lock");
        assertThat(collisions.get(0).module()).isEqualTo("runtime/mcp");
    }

    @Test
    void detects_nested_module_scope_collisions() {
        List<BranchLockCollision> collisions = BranchLock.detect_branch_lock_collisions(List.of(
                new BranchLockIntent("lane-a", "feature/lock", (String) null, List.of("runtime")),
                new BranchLockIntent("lane-b", "feature/lock", (String) null, List.of("runtime/mcp"))));

        assertThat(collisions.get(0).module()).isEqualTo("runtime");
    }

    @Test
    void ignores_different_branches() {
        List<BranchLockCollision> collisions = BranchLock.detect_branch_lock_collisions(List.of(
                new BranchLockIntent("lane-a", "feature/a", (String) null, List.of("runtime/mcp")),
                new BranchLockIntent("lane-b", "feature/b", (String) null, List.of("runtime/mcp"))));

        assertThat(collisions).isEmpty();
    }
}
