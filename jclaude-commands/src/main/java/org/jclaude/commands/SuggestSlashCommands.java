package org.jclaude.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Levenshtein-based slash-command suggestion heuristic. Mirrors Rust
 * {@code suggest_slash_commands}.
 */
public final class SuggestSlashCommands {

    private SuggestSlashCommands() {}

    static int levenshtein_distance(String left, String right) {
        if (left.equals(right)) {
            return 0;
        }
        if (left.isEmpty()) {
            return right.length();
        }
        if (right.isEmpty()) {
            return left.length();
        }
        int n = right.length();
        int[] previous = new int[n + 1];
        int[] current = new int[n + 1];
        for (int i = 0; i <= n; i++) {
            previous[i] = i;
        }
        for (int i = 0; i < left.length(); i++) {
            current[0] = i + 1;
            char leftChar = left.charAt(i);
            for (int j = 0; j < n; j++) {
                int substitution_cost = leftChar == right.charAt(j) ? 0 : 1;
                current[j + 1] =
                        Math.min(Math.min(current[j] + 1, previous[j + 1] + 1), previous[j] + substitution_cost);
            }
            System.arraycopy(current, 0, previous, 0, n + 1);
        }
        return previous[n];
    }

    /** Returns up to {@code limit} suggestions sorted by closeness. */
    public static List<String> suggest_slash_commands(String input, int limit) {
        if (input == null) {
            return List.of();
        }
        String query = input.trim();
        while (query.startsWith("/")) {
            query = query.substring(1);
        }
        query = query.toLowerCase(Locale.ROOT);
        if (query.isEmpty() || limit == 0) {
            return List.of();
        }

        record Candidate(int prefix_rank, int distance, int name_len, String name) {}
        List<Candidate> candidates = new ArrayList<>();
        for (SlashCommandSpec spec : SlashCommandSpecs.slash_command_specs()) {
            int bestPrefixRank = Integer.MAX_VALUE;
            int bestDistance = Integer.MAX_VALUE;
            List<String> names = new ArrayList<>();
            names.add(spec.name());
            names.addAll(spec.aliases());
            for (String n : names) {
                String candidate = n.toLowerCase(Locale.ROOT);
                int prefix_rank;
                if (candidate.startsWith(query) || query.startsWith(candidate)) {
                    prefix_rank = 0;
                } else if (candidate.contains(query) || query.contains(candidate)) {
                    prefix_rank = 1;
                } else {
                    prefix_rank = 2;
                }
                int distance = levenshtein_distance(candidate, query);
                if (prefix_rank < bestPrefixRank || (prefix_rank == bestPrefixRank && distance < bestDistance)) {
                    bestPrefixRank = prefix_rank;
                    bestDistance = distance;
                }
            }
            if (bestPrefixRank <= 1 || bestDistance <= 2) {
                candidates.add(
                        new Candidate(bestPrefixRank, bestDistance, spec.name().length(), spec.name()));
            }
        }
        Collections.sort(
                candidates,
                Comparator.<Candidate>comparingInt(c -> c.prefix_rank())
                        .thenComparingInt(c -> c.distance())
                        .thenComparingInt(c -> c.name_len())
                        .thenComparing(c -> c.name()));
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, candidates.size()); i++) {
            result.add("/" + candidates.get(i).name());
        }
        return result;
    }
}
