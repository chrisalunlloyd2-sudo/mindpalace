package com.mindpalace.world;

import java.util.*;

/**
 * LegacyRepoClassifier — marks repos as "legacy" (archived/backup/duplicate)
 * so the agent's issue-finding loop skips them and focuses on active work.
 *
 * The user's spec: "find and solve issues in all new NON-legacy repos" — i.e.
 * the agent should NOT waste cycles on backups, refs, throwaway test projects,
 * or case/hyphen/underscore duplicates of the same repo.
 *
 * Heuristics (all name-based, deterministic, no git/network):
 *   1. Explicit markers: "backup", "ref", "old", "archive", "legacy", "deprecated"
 *   2. Throwaway test scaffolding: "test", "e2e", "random", "scratch", "tmp", "sample"
 *   3. Duplicate of another repo (case/hyphen/underscore-insensitive match)
 */
public final class LegacyRepoClassifier {

    private LegacyRepoClassifier() {}

    private static final String[] MARKERS = {
        "backup", "ref", "old", "archive", "legacy", "deprecated", "merged",
        "test", "e2e", "random", "scratch", "tmp", "sample", "demo", "sandbox"
    };

    /** Normalize a repo name for duplicate detection: lowercase, strip -_. */
    public static String canonical(String name) {
        if (name == null) return "";
        return name.toLowerCase().replaceAll("[-_.]", "");
    }

    /** Is this repo name a legacy/throwaway/duplicate? */
    public static boolean isLegacy(String name, Collection<String> allNames) {
        if (name == null || name.isEmpty()) return true;
        String lower = name.toLowerCase();

        // 1. Explicit markers
        for (String m : MARKERS) {
            if (lower.contains(m)) return true;
        }

        // 2. Duplicate detection: if another repo canonicalizes to the same key
        //    and sorts earlier (the "primary"), this one is the duplicate.
        String mine = canonical(name);
        for (String other : allNames) {
            if (other == null || other.equals(name)) continue;
            if (canonical(other).equals(mine)) {
                // The lexicographically-first name is the primary; the rest are legacy
                if (other.compareTo(name) < 0) return true;
            }
        }
        return false;
    }

    /** Partition a list of repo names into active (non-legacy) and legacy. */
    public static Map<String, List<String>> partition(Collection<String> names) {
        List<String> active = new ArrayList<>();
        List<String> legacy = new ArrayList<>();
        for (String n : names) {
            if (isLegacy(n, names)) legacy.add(n);
            else active.add(n);
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("active", active);
        out.put("legacy", legacy);
        return out;
    }
}
