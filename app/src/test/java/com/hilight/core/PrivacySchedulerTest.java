package com.hilight.core;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class PrivacySchedulerTest {

    private static final PrivacyScheduler.Rule MIC_ANY =
            new PrivacyScheduler.Rule("mic-any", PrivacyScheduler.Activity.MICROPHONE, "*", 10_000, 10_000);
    private static final PrivacyScheduler.Rule CAMERA_ANY =
            new PrivacyScheduler.Rule("camera-any", PrivacyScheduler.Activity.CAMERA, "*", 10_000, 10_000);

    @Test
    public void hiLightsOwnMusicSyncListeningIsIgnored() {
        PrivacyScheduler s = scheduler(MIC_ANY);
        s.updateActive(set(use(PrivacyScheduler.Activity.MICROPHONE, 10050, PrivacyScheduler.OWN_PACKAGE)), 1_000);
        assertPhase(s, 1_000, PrivacyScheduler.Phase.INACTIVE);
        s.updateActive(set(
                use(PrivacyScheduler.Activity.MICROPHONE, 10050, PrivacyScheduler.OWN_PACKAGE),
                use(PrivacyScheduler.Activity.MICROPHONE, 10001, "recorder")), 2_000);
        assertPhase(s, 2_000, PrivacyScheduler.Phase.LIT);
    }

    @Test
    public void defaultRhythmStopsAtOneMinute() {
        PrivacyScheduler s = scheduler(MIC_ANY);
        s.updateActive(set(use(PrivacyScheduler.Activity.MICROPHONE, 10001, "recorder")), 1_000);

        assertPhase(s, 1_000, PrivacyScheduler.Phase.LIT);
        assertPhase(s, 10_999, PrivacyScheduler.Phase.LIT);
        assertPhase(s, 11_000, PrivacyScheduler.Phase.COOLDOWN);
        assertPhase(s, 21_000, PrivacyScheduler.Phase.LIT);
        assertPhase(s, 31_000, PrivacyScheduler.Phase.COOLDOWN);
        assertPhase(s, 41_000, PrivacyScheduler.Phase.LIT);
        assertPhase(s, 51_000, PrivacyScheduler.Phase.COOLDOWN);
        assertPhase(s, 60_999, PrivacyScheduler.Phase.COOLDOWN);
        assertPhase(s, 61_000, PrivacyScheduler.Phase.EXHAUSTED);
        assertPhase(s, 90_000, PrivacyScheduler.Phase.EXHAUSTED);
    }

    @Test
    public void stoppingAfterFiveSecondsEndsImmediatelyAndNextStartGetsNewEpisode() {
        PrivacyScheduler s = scheduler(MIC_ANY);
        PrivacyScheduler.Use use = use(PrivacyScheduler.Activity.MICROPHONE, 10001, "recorder");
        s.updateActive(set(use), 0);
        assertPhase(s, 5_000, PrivacyScheduler.Phase.LIT);

        s.updateActive(Collections.emptySet(), 5_000);
        assertPhase(s, 5_000, PrivacyScheduler.Phase.INACTIVE);

        s.updateActive(set(use), 8_000);
        assertPhase(s, 8_000, PrivacyScheduler.Phase.LIT);
        assertPhase(s, 68_000, PrivacyScheduler.Phase.EXHAUSTED);
    }

    @Test
    public void exactRuleBeatsAnyAndMostRecentlyActivatedExactWins() {
        PrivacyScheduler.Rule a = new PrivacyScheduler.Rule(
                "a", PrivacyScheduler.Activity.MICROPHONE, "app.a", 5_000, 5_000);
        PrivacyScheduler.Rule b = new PrivacyScheduler.Rule(
                "b", PrivacyScheduler.Activity.MICROPHONE, "app.b", 7_000, 3_000);
        PrivacyScheduler s = scheduler(MIC_ANY, a, b);

        s.updateActive(set(use(PrivacyScheduler.Activity.MICROPHONE, 1, "app.a")), 0);
        assertEquals("a", s.decision(1_000).ruleId);

        s.updateActive(set(
                use(PrivacyScheduler.Activity.MICROPHONE, 1, "app.a"),
                use(PrivacyScheduler.Activity.MICROPHONE, 2, "app.b")), 2_000);
        assertEquals("b", s.decision(2_000).ruleId);

        s.updateActive(set(use(PrivacyScheduler.Activity.MICROPHONE, 1, "app.a")), 4_000);
        assertEquals("a", s.decision(4_000).ruleId);
    }

    @Test
    public void anyAppUsesOneActivityWideClockAcrossPackageHandoff() {
        PrivacyScheduler s = scheduler(MIC_ANY);
        PrivacyScheduler.Use a = use(PrivacyScheduler.Activity.MICROPHONE, 1, "app.a");
        PrivacyScheduler.Use b = use(PrivacyScheduler.Activity.MICROPHONE, 2, "app.b");

        s.updateActive(set(a), 0);
        s.updateActive(set(a, b), 40_000);
        s.updateActive(set(b), 50_000);
        assertPhase(s, 60_000, PrivacyScheduler.Phase.EXHAUSTED);

        s.updateActive(Collections.emptySet(), 61_000);
        s.updateActive(set(b), 70_000);
        assertPhase(s, 70_000, PrivacyScheduler.Phase.LIT);
    }

    @Test
    public void cameraOnlyWinsWhenItHasEligibleRule() {
        PrivacyScheduler s = scheduler(MIC_ANY);
        s.updateActive(set(
                use(PrivacyScheduler.Activity.MICROPHONE, 1, "video"),
                use(PrivacyScheduler.Activity.CAMERA, 1, "video")), 0);
        assertEquals(PrivacyScheduler.Activity.MICROPHONE, s.decision(1_000).activity);

        s.setRules(Arrays.asList(MIC_ANY, CAMERA_ANY));
        assertEquals(PrivacyScheduler.Activity.CAMERA, s.decision(1_000).activity);
        assertPhase(s, 60_000, PrivacyScheduler.Phase.EXHAUSTED);
    }

    @Test
    public void higherLayerDoesNotPausePrivacyClock() {
        PrivacyScheduler s = scheduler(MIC_ANY);
        s.updateActive(set(use(PrivacyScheduler.Activity.MICROPHONE, 1, "recorder")), 0);

        assertPhase(s, 9_000, PrivacyScheduler.Phase.LIT);
        // Nothing calls the scheduler for 17 seconds while a notification is shown.
        assertPhase(s, 26_000, PrivacyScheduler.Phase.LIT);
        assertPhase(s, 36_000, PrivacyScheduler.Phase.COOLDOWN);
    }

    private static PrivacyScheduler scheduler(PrivacyScheduler.Rule... rules) {
        PrivacyScheduler s = new PrivacyScheduler();
        s.setRules(Arrays.asList(rules));
        return s;
    }

    private static PrivacyScheduler.Use use(PrivacyScheduler.Activity activity, int uid, String pkg) {
        return new PrivacyScheduler.Use(activity, uid, pkg);
    }

    @SafeVarargs
    private static <T> Set<T> set(T... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static void assertPhase(PrivacyScheduler s, long now, PrivacyScheduler.Phase phase) {
        assertEquals(phase, s.decision(now).phase);
    }
}
