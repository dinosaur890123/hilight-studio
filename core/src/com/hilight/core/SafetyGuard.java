package com.hilight.core;

/**
 * Pure, stateful safety limiter for the LED renderer.
 *
 * Keeping this separate from Android and binder work makes the timing limits directly testable.
 */
final class SafetyGuard {

    static final long FRAME_MS = 33;
    static final long DUTY_WINDOW_MS = 10 * 60_000;
    static final double MAX_DUTY = 0.5;
    static final long TAPER_AFTER_MS = 10_000;
    static final long TAPER_RAMP_MS = 10_000;
    static final double TAPER_FLOOR = 0.55;
    /**
     * The test bench's heat experiment may run without the taper or the duty rest, but only for this
     * much lit time, and that allowance only refills after {@link #EXPERIMENT_COOLDOWN_MS} with no
     * experiment frames at all. The duty window still counts the light, so ordinary output rests
     * afterwards until the window allows it again.
     */
    static final long EXPERIMENT_MAX_MS = 11 * 60_000;
    static final long EXPERIMENT_COOLDOWN_MS = 15 * 60_000;

    private final long dutyWindowMs;
    private final double maxDuty;
    private final long taperAfterMs;
    private final long taperRampMs;
    private final double taperFloor;
    private final long experimentMaxMs;
    private final long experimentCooldownMs;

    private long windowStart = Long.MIN_VALUE;
    private long lastAppliedAt = Long.MIN_VALUE;
    private long litMsInWindow;
    private long continuousLitMs;
    private boolean lastOutputVisible;
    private boolean resting;
    private long experimentLitMs;
    private long lastExperimentAt = Long.MIN_VALUE;
    private boolean lastFrameExperiment;

    SafetyGuard() {
        this(DUTY_WINDOW_MS, MAX_DUTY, TAPER_AFTER_MS, TAPER_RAMP_MS, TAPER_FLOOR);
    }

    SafetyGuard(
            long dutyWindowMs,
            double maxDuty,
            long taperAfterMs,
            long taperRampMs,
            double taperFloor
    ) {
        this(dutyWindowMs, maxDuty, taperAfterMs, taperRampMs, taperFloor,
                EXPERIMENT_MAX_MS, EXPERIMENT_COOLDOWN_MS);
    }

    SafetyGuard(
            long dutyWindowMs,
            double maxDuty,
            long taperAfterMs,
            long taperRampMs,
            double taperFloor,
            long experimentMaxMs,
            long experimentCooldownMs
    ) {
        if (dutyWindowMs <= 0 || maxDuty <= 0 || maxDuty > 1
                || taperAfterMs < 0 || taperRampMs <= 0 || taperFloor < 0 || taperFloor > 1
                || experimentMaxMs < 0 || experimentCooldownMs <= 0) {
            throw new IllegalArgumentException("Invalid safety limits");
        }
        this.dutyWindowMs = dutyWindowMs;
        this.maxDuty = maxDuty;
        this.taperAfterMs = taperAfterMs;
        this.taperRampMs = taperRampMs;
        this.taperFloor = taperFloor;
        this.experimentMaxMs = experimentMaxMs;
        this.experimentCooldownMs = experimentCooldownMs;
    }

    /** Applies limits using monotonic elapsed realtime supplied by the renderer. */
    int[] apply(int[] frame, long elapsedRealtime, double dim) {
        return apply(frame, elapsedRealtime, dim, false);
    }

    /**
     * As {@link #apply(int[], long, double)}; an {@code experiment} frame skips the taper and the
     * duty rest while the bounded experiment allowance lasts, and is otherwise limited as usual.
     */
    int[] apply(int[] frame, long elapsedRealtime, double dim, boolean experiment) {
        if (lastAppliedAt != Long.MIN_VALUE && elapsedRealtime < lastAppliedAt) {
            // elapsedRealtime() cannot move backwards in production. Clamping keeps a malformed
            // host input from subtracting already-accounted light time or extending a limit.
            elapsedRealtime = lastAppliedAt;
        }
        accountElapsedTime(elapsedRealtime);

        boolean bypass = false;
        if (experiment) {
            if (lastExperimentAt != Long.MIN_VALUE
                    && elapsedRealtime - lastExperimentAt > experimentCooldownMs) {
                experimentLitMs = 0;
            }
            lastExperimentAt = elapsedRealtime;
            bypass = experimentLitMs < experimentMaxMs;
        }
        lastFrameExperiment = experiment;

        if (!FrameVisibility.isVisible(frame)) {
            noteOutput(false);
            return frame;
        }

        if (dim < 0.999) {
            int[] dimmed = new int[frame.length];
            for (int i = 0; i < frame.length; i++) dimmed[i] = Renderer.scale(frame[i], dim);
            frame = dimmed;
        }

        if (bypass) {
            noteOutput(true);
            return frame;
        }

        if (resting || litMsInWindow >= dutyWindowMs * maxDuty) {
            resting = true;
            noteOutput(false);
            return new int[]{0};
        }

        if (continuousLitMs <= taperAfterMs) {
            noteOutput(FrameVisibility.isVisible(frame));
            return frame;
        }

        double over = Math.min(1.0, (continuousLitMs - taperAfterMs) / (double) taperRampMs);
        double scale = 1.0 - (1.0 - taperFloor) * over;
        int[] out = new int[frame.length];
        for (int i = 0; i < frame.length; i++) out[i] = Renderer.scale(frame[i], scale);
        noteOutput(FrameVisibility.isVisible(out));
        return out;
    }

    /** Accounts how long the previous output remained latched between renderer calls. */
    private void accountElapsedTime(long elapsedRealtime) {
        if (windowStart == Long.MIN_VALUE) {
            windowStart = elapsedRealtime;
        }
        if (lastAppliedAt == Long.MIN_VALUE) {
            lastAppliedAt = elapsedRealtime;
            return;
        }

        long delta = elapsedRealtime - lastAppliedAt;
        if (lastOutputVisible) {
            continuousLitMs = saturatingAdd(continuousLitMs, delta);
            if (lastFrameExperiment) experimentLitMs = saturatingAdd(experimentLitMs, delta);
        }

        long sinceWindowStart = elapsedRealtime - windowStart;
        if (sinceWindowStart >= dutyWindowMs) {
            long completedWindows = sinceWindowStart / dutyWindowMs;
            long newWindowStart = windowStart + completedWindows * dutyWindowMs;
            boolean observedPriorWindowOverrun = false;
            if (lastOutputVisible) {
                long firstWindowEnd = windowStart + dutyWindowMs;
                long priorWindowTail = Math.max(
                        0,
                        Math.min(elapsedRealtime, firstWindowEnd) - lastAppliedAt
                );
                observedPriorWindowOverrun = completedWindows > 1
                        || saturatingAdd(litMsInWindow, priorWindowTail)
                        > dutyWindowMs * maxDuty;
            }
            litMsInWindow = lastOutputVisible
                    ? elapsedRealtime - Math.max(lastAppliedAt, newWindowStart)
                    : 0;
            windowStart = newWindowStart;
            // If a stalled renderer let a visible frame overrun an earlier window, fail dark for
            // this window rather than treating the already-violating gap as a fresh budget.
            resting = observedPriorWindowOverrun;
        } else if (lastOutputVisible) {
            litMsInWindow = saturatingAdd(litMsInWindow, delta);
        }
        lastAppliedAt = elapsedRealtime;
    }

    private void noteOutput(boolean visible) {
        lastOutputVisible = visible;
        if (!visible) {
            continuousLitMs = 0;
        }
    }

    private static long saturatingAdd(long value, long delta) {
        return value > Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta;
    }

    /** Lit time spent from the current experiment allowance, for status reporting. */
    long experimentLitMs() {
        return experimentLitMs;
    }

    boolean isResting() {
        return resting;
    }

    int dutyPercent() {
        return (int) (100.0 * litMsInWindow / (dutyWindowMs * maxDuty));
    }
}
