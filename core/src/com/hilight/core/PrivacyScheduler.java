package com.hilight.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure timing and matching for continuous microphone/camera activity.
 *
 * <p>All time is supplied by the caller and must be monotonic. Package episodes begin when that
 * package first appears in the active snapshot. Any-app episodes begin when the activity-wide set
 * changes from empty to non-empty, so overlapping apps cannot keep granting fresh one-minute runs.
 */
public final class PrivacyScheduler {

    public static final long EPISODE_MAX_MS = 60_000;
    public static final String ANY_APP = "*";

    public enum Activity { MICROPHONE, CAMERA }
    public enum Phase { INACTIVE, LIT, COOLDOWN, EXHAUSTED }

    public static final class Use {
        public final Activity activity;
        public final int uid;
        public final String packageName;

        public Use(Activity activity, int uid, String packageName) {
            this.activity = Objects.requireNonNull(activity);
            this.uid = uid;
            this.packageName = Objects.requireNonNull(packageName);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Use)) return false;
            Use that = (Use) other;
            return activity == that.activity && uid == that.uid && packageName.equals(that.packageName);
        }

        @Override public int hashCode() {
            return Objects.hash(activity, uid, packageName);
        }
    }

    public static final class Rule {
        public final String id;
        public final Activity activity;
        public final String packageName;
        public final long lightMs;
        public final long cooldownMs;

        public Rule(String id, Activity activity, String packageName, long lightMs, long cooldownMs) {
            this.id = Objects.requireNonNull(id);
            this.activity = Objects.requireNonNull(activity);
            this.packageName = Objects.requireNonNull(packageName);
            this.lightMs = Math.max(1, lightMs);
            this.cooldownMs = Math.max(1, cooldownMs);
        }
    }

    public static final class Decision {
        public final Phase phase;
        public final String ruleId;
        public final Activity activity;
        public final long episodeElapsedMs;
        public final long phaseElapsedMs;

        private Decision(Phase phase, String ruleId, Activity activity,
                         long episodeElapsedMs, long phaseElapsedMs) {
            this.phase = phase;
            this.ruleId = ruleId;
            this.activity = activity;
            this.episodeElapsedMs = episodeElapsedMs;
            this.phaseElapsedMs = phaseElapsedMs;
        }

        private static Decision inactive() {
            return new Decision(Phase.INACTIVE, null, null, 0, 0);
        }

        private static Decision exhausted(Activity activity) {
            return new Decision(Phase.EXHAUSTED, null, activity, EPISODE_MAX_MS, 0);
        }
    }

    private static final class Episode {
        final long startedAt;
        Episode(long startedAt) { this.startedAt = startedAt; }
    }

    private final EnumMap<Activity, Map<String, Episode>> packages =
            new EnumMap<>(Activity.class);
    private final EnumMap<Activity, Episode> anyEpisodes = new EnumMap<>(Activity.class);
    private List<Rule> rules = Collections.emptyList();

    public PrivacyScheduler() {
        for (Activity activity : Activity.values()) packages.put(activity, new HashMap<>());
    }

    public void setRules(List<Rule> rules) {
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
    }

    /** HiLight Studio itself; its own music-sync listening is not a privacy event to signal. */
    static final String OWN_PACKAGE = "com.hilight.studio";

    /** Reconciles an authoritative current snapshot; callbacks must not be applied as reference counts. */
    public void updateActive(Set<Use> uses, long now) {
        EnumMap<Activity, Set<String>> next = new EnumMap<>(Activity.class);
        for (Activity activity : Activity.values()) next.put(activity, new HashSet<>());
        for (Use use : uses) {
            if (!OWN_PACKAGE.equals(use.packageName)) next.get(use.activity).add(use.packageName);
        }

        for (Activity activity : Activity.values()) {
            Map<String, Episode> current = packages.get(activity);
            Set<String> active = next.get(activity);
            boolean wasEmpty = current.isEmpty();

            current.keySet().removeIf(pkg -> !active.contains(pkg));
            for (String pkg : active) current.putIfAbsent(pkg, new Episode(now));

            if (active.isEmpty()) {
                anyEpisodes.remove(activity);
            } else if (wasEmpty) {
                anyEpisodes.put(activity, new Episode(now));
            }
        }
    }

    public void clearActive() {
        for (Map<String, Episode> entries : packages.values()) entries.clear();
        anyEpisodes.clear();
    }

    public Decision decision(long now) {
        Selection camera = select(Activity.CAMERA, now);
        if (camera.eligible != null) return decide(camera, now);
        Selection microphone = select(Activity.MICROPHONE, now);
        if (microphone.eligible != null) return decide(microphone, now);
        if (camera.hadExhaustedMatch) return Decision.exhausted(Activity.CAMERA);
        if (microphone.hadExhaustedMatch) return Decision.exhausted(Activity.MICROPHONE);
        return Decision.inactive();
    }

    private static final class Selection {
        Rule eligible;
        Episode episode;
        boolean hadExhaustedMatch;
    }

    private Selection select(Activity activity, long now) {
        Selection out = new Selection();
        Map<String, Episode> active = packages.get(activity);
        if (active.isEmpty()) return out;

        List<Map.Entry<String, Episode>> exact = new ArrayList<>(active.entrySet());
        exact.sort(Comparator
                .<Map.Entry<String, Episode>>comparingLong(e -> e.getValue().startedAt).reversed()
                .thenComparing(Map.Entry::getKey));

        for (Map.Entry<String, Episode> entry : exact) {
            Rule rule = ruleFor(activity, entry.getKey());
            if (rule == null) continue;
            if (elapsed(entry.getValue(), now) < EPISODE_MAX_MS) {
                out.eligible = rule;
                out.episode = entry.getValue();
                return out;
            }
            out.hadExhaustedMatch = true;
        }

        Rule any = ruleFor(activity, ANY_APP);
        Episode anyEpisode = anyEpisodes.get(activity);
        if (any != null && anyEpisode != null) {
            if (elapsed(anyEpisode, now) < EPISODE_MAX_MS) {
                out.eligible = any;
                out.episode = anyEpisode;
            } else {
                out.hadExhaustedMatch = true;
            }
        }
        return out;
    }

    private Rule ruleFor(Activity activity, String pkg) {
        Rule best = null;
        for (Rule rule : rules) {
            if (rule.activity != activity || !rule.packageName.equals(pkg)) continue;
            if (best == null || rule.id.compareTo(best.id) < 0) best = rule;
        }
        return best;
    }

    private Decision decide(Selection selection, long now) {
        long episodeElapsed = elapsed(selection.episode, now);
        long cycle = selection.eligible.lightMs + selection.eligible.cooldownMs;
        long position = episodeElapsed % cycle;
        Phase phase = position < selection.eligible.lightMs ? Phase.LIT : Phase.COOLDOWN;
        long phaseElapsed = phase == Phase.LIT ? position : position - selection.eligible.lightMs;
        return new Decision(
                phase, selection.eligible.id, selection.eligible.activity,
                episodeElapsed, phaseElapsed);
    }

    private static long elapsed(Episode episode, long now) {
        return Math.max(0, now - episode.startedAt);
    }
}
