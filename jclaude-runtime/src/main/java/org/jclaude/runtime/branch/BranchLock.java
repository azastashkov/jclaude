package org.jclaude.runtime.branch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** Branch-lock collision detector mirroring the Rust algorithm. */
public final class BranchLock {

    private BranchLock() {}

    public static List<BranchLockCollision> detect_branch_lock_collisions(List<BranchLockIntent> intents) {
        List<BranchLockCollision> collisions = new ArrayList<>();

        for (int index = 0; index < intents.size(); index++) {
            BranchLockIntent left = intents.get(index);
            for (int j = index + 1; j < intents.size(); j++) {
                BranchLockIntent right = intents.get(j);
                if (!left.branch().equals(right.branch())) {
                    continue;
                }
                for (String module : overlapping_modules(left.modules(), right.modules())) {
                    collisions.add(
                            new BranchLockCollision(left.branch(), module, List.of(left.lane_id(), right.lane_id())));
                }
            }
        }

        collisions.sort(Comparator.comparing(BranchLockCollision::branch)
                .thenComparing(BranchLockCollision::module)
                .thenComparing(c -> String.join(",", c.lane_ids())));

        List<BranchLockCollision> deduped = new ArrayList<>();
        for (BranchLockCollision c : collisions) {
            if (deduped.isEmpty() || !deduped.get(deduped.size() - 1).equals(c)) {
                deduped.add(c);
            }
        }
        return deduped;
    }

    private static List<String> overlapping_modules(List<String> left, List<String> right) {
        TreeSet<String> overlaps = new TreeSet<>();
        for (String left_module : left) {
            for (String right_module : right) {
                if (modules_overlap(left_module, right_module)) {
                    overlaps.add(shared_scope(left_module, right_module));
                }
            }
        }
        return new ArrayList<>(overlaps);
    }

    private static boolean modules_overlap(String left, String right) {
        return left.equals(right) || left.startsWith(right + "/") || right.startsWith(left + "/");
    }

    private static String shared_scope(String left, String right) {
        if (left.startsWith(right + "/") || left.equals(right)) {
            return right;
        }
        return left;
    }
}
