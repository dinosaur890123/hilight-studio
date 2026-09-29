package com.hilight.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;

/**
 * Turns a pattern config into one frame of LED colours.
 *
 * Config keys: "mode" (ambient) or "pattern" (alert), "color" or "colors", "brightness", "speedMs",
 * "spread", "rotateMs", and the random-mode keys "randomIntervalMs" / "randomPerLed" /
 * "randomSmooth" / "randomSaturation".
 *
 * The app mirrors this maths in Kotlin for its on-screen preview; keep the two in step.
 */
public final class Renderer {

    /** Width, in LEDs, of one colour-pair period of the marquee bands. */
    static final int MARQUEE_BAND = 4;

    private final Random rnd;

    // random-mode fade state
    private int[] randFrom, randTo;
    private long randStart, randDuration = 1500;

    public Renderer() {
        this(new Random());
    }

    /** Deterministic host-test constructor. */
    Renderer(Random rnd) {
        this.rnd = rnd;
    }

    /** Discards animation state so the next frame starts a pattern cleanly. */
    public void reset() {
        randFrom = null;
        randTo = null;
    }

    /** Renders a frame at a caller-supplied monotonic animation time. */
    public int[] frame(JSONObject cfg, long t, int n) {
        int[] out = new int[n];
        if (cfg == null) return out;

        // ambient configs carry "mode", alerts carry "pattern" — accept either
        String mode = cfg.optString("mode", cfg.optString("pattern", "off"));
        double bright = clamp01(cfg.optDouble("brightness", 1.0));
        long speed = Math.max(60, cfg.optLong("speedMs", 2000));
        int[] palette = colors(cfg);

        switch (mode) {
            case "off":
                break;

            case "solid":
                for (int i = 0; i < n; i++) out[i] = palette[i % palette.length];
                break;

            case "gradient": {
                int a = palette[0];
                int b = palette.length > 1 ? palette[1] : a;
                for (int i = 0; i < n; i++) out[i] = mix(a, b, n == 1 ? 0 : (double) i / (n - 1));
                break;
            }

            case "breathe": {
                double phase = (t % speed) / (double) speed;
                double k = (1 - Math.cos(phase * 2 * Math.PI)) / 2;
                for (int i = 0; i < n; i++) out[i] = scale(palette[i % palette.length], 0.05 + 0.95 * k);
                break;
            }

            case "blink": {
                if ((t % speed) < speed / 2) {
                    for (int i = 0; i < n; i++) out[i] = palette[i % palette.length];
                }
                break;
            }

            case "pulse": {
                // sharp attack, exponential decay — reads well as a notification
                double phase = (t % speed) / (double) speed;
                double k = phase < 0.12 ? phase / 0.12 : Math.exp(-(phase - 0.12) * 5);
                for (int i = 0; i < n; i++) out[i] = scale(palette[i % palette.length], k);
                break;
            }

            case "chase": {
                int head = (int) ((t / Math.max(1, speed / n)) % n);
                for (int i = 0; i < n; i++) out[i] = i == head ? palette[0] : 0xFF000000;
                break;
            }

            case "comet": {
                double pos = (t % speed) / (double) speed * n;
                for (int i = 0; i < n; i++) {
                    double d = pos - i;
                    if (d < 0) d += n;
                    out[i] = scale(palette[i % palette.length], Math.max(0, 1 - d / 3.0));
                }
                break;
            }

            case "wave": {
                double phase = (t % speed) / (double) speed;
                for (int i = 0; i < n; i++) {
                    double k = (1 + Math.sin(2 * Math.PI * (phase + (double) i / n))) / 2;
                    out[i] = scale(palette[i % palette.length], 0.08 + 0.92 * k);
                }
                break;
            }

            case "rainbow": {
                double phase = (t % speed) / (double) speed;
                boolean spread = cfg.optBoolean("spread", true);
                for (int i = 0; i < n; i++) {
                    double h = (phase + (spread ? (double) i / n : 0)) * 360.0;
                    out[i] = hsv(h % 360, 1f, 1f);
                }
                break;
            }

            case "meter": {
                double phase = (t % speed) / (double) speed;
                if (phase < 0.75) {
                    double progress = (phase / 0.75) * n;
                    int fullCount = (int) Math.floor(progress);
                    double partial = progress - fullCount;
                    for (int i = 0; i < n; i++) {
                        if (i < fullCount) {
                            out[i] = palette[i % palette.length];
                        } else if (i == fullCount) {
                            out[i] = scale(palette[i % palette.length], partial);
                        } else {
                            out[i] = 0xFF000000;
                        }
                    }
                } else if (phase < 0.88) {
                    for (int i = 0; i < n; i++) out[i] = palette[i % palette.length];
                } else {
                    double fade = 1.0 - (phase - 0.88) / 0.12;
                    for (int i = 0; i < n; i++) out[i] = scale(palette[i % palette.length], fade);
                }
                break;
            }

            case "strobe": {
                double phase = (t % speed) / (double) speed;
                if (phase < 0.45) {
                    double subPhase = (phase / 0.45) * 3.0;
                    double frac = subPhase - Math.floor(subPhase);
                    if (frac < 0.55) {
                        for (int i = 0; i < n; i++) out[i] = palette[i % palette.length];
                    }
                }
                break;
            }

            case "heartbeat": {
                double phase = (t % speed) / (double) speed;
                double k;
                if (phase < 0.06) {
                    k = (phase / 0.06) * 0.75;
                } else if (phase < 0.22) {
                    k = 0.75 * Math.exp(-(phase - 0.06) * 16.0);
                } else if (phase < 0.28) {
                    k = ((phase - 0.22) / 0.06);
                } else if (phase < 0.60) {
                    k = Math.exp(-(phase - 0.28) * 9.0);
                } else {
                    k = 0.0;
                }
                for (int i = 0; i < n; i++) out[i] = scale(palette[i % palette.length], k);
                break;
            }

            case "bounce": {
                double phase = (t % speed) / (double) speed;
                double pos = (phase < 0.5 ? (phase * 2.0) : ((1.0 - phase) * 2.0)) * (n - 1);
                for (int i = 0; i < n; i++) {
                    double dist = Math.abs(pos - i);
                    out[i] = scale(palette[i % palette.length], Math.max(0.0, 1.0 - dist / 1.5));
                }
                break;
            }

            case "radar": {
                double phase = (t % speed) / (double) speed;
                double head = phase * n;
                for (int i = 0; i < n; i++) {
                    double d = head - i;
                    if (d < 0) d += n;
                    out[i] = scale(palette[i % palette.length], Math.exp(-d * 0.55));
                }
                break;
            }

            case "converge": {
                double phase = (t % speed) / (double) speed;
                double travel = phase < 0.5 ? (phase * 2.0) : ((1.0 - phase) * 2.0);
                double centerDist = (n - 1) / 2.0;
                double p1 = travel * centerDist;
                double p2 = (n - 1) - travel * centerDist;
                double boost = travel > 0.85 ? (travel - 0.85) / 0.15 * 0.35 : 0.0;
                for (int i = 0; i < n; i++) {
                    double d1 = Math.abs(p1 - i);
                    double d2 = Math.abs(p2 - i);
                    double k = Math.max(Math.max(0.0, 1.0 - d1), Math.max(0.0, 1.0 - d2)) + boost;
                    out[i] = scale(palette[i % palette.length], Math.min(1.0, k));
                }
                break;
            }

            case "glitch": {
                for (int i = 0; i < n; i++) {
                    int seed = (i * 3 + 1) * 7;
                    long ledPeriod = Math.max(80, speed / 2 + (seed % 5) * 80);
                    double ledPhase = ((t + seed * 137L) % ledPeriod) / (double) ledPeriod;
                    double spike = ledPhase < 0.15 ? (ledPhase / 0.15) : Math.exp(-(ledPhase - 0.15) * 12.0);
                    double jitter = ((t / 40 + i * 5) % 3 == 0 && spike > 0.05) ? 0.3 : 0.0;
                    double k = clamp01(spike * 0.85 + jitter);
                    out[i] = scale(palette[i % palette.length], k);
                }
                break;
            }

            case "aurora": {
                // two colours drifting through each other, with a slower shimmer riding on top
                int a = palette[0];
                int b = palette.length > 1 ? palette[1] : a;
                double phase = (t % speed) / (double) speed;
                for (int i = 0; i < n; i++) {
                    double m = (1 + Math.sin(2 * Math.PI * (phase + (double) i / n))) / 2;
                    double s = (1 + Math.sin(2 * Math.PI * 2 * phase + i * 1.3)) / 2;
                    out[i] = scale(mix(a, b, m), 0.35 + 0.65 * s);
                }
                break;
            }

            case "crossfade": {
                // the whole array eases from the first colour to the second and back
                int a = palette[0];
                int b = palette.length > 1 ? palette[1] : a;
                double phase = (t % speed) / (double) speed;
                int c = mix(a, b, (1 - Math.cos(phase * 2 * Math.PI)) / 2);
                for (int i = 0; i < n; i++) out[i] = c;
                break;
            }

            case "marquee": {
                // alternating two-LED bands of each colour, sliding one band pair per cycle
                int a = palette[0];
                int b = palette.length > 1 ? palette[1] : a;
                double phase = (t % speed) / (double) speed;
                for (int i = 0; i < n; i++) {
                    double m = clamp01(0.5 + 1.2 * Math.cos(2 * Math.PI * ((double) i / MARQUEE_BAND - phase)));
                    out[i] = mix(b, a, m);
                }
                break;
            }

            case "twinkle": {
                // each LED sparkles on its own rhythm over a faint glow, flaring towards white at the peak
                for (int i = 0; i < n; i++) {
                    long period = Math.max(120, speed * (5 + (i * 3) % 4) / 6);
                    long offset = speed * ((i * 5) % 8) / 8;
                    double ledPhase = ((t + offset) % period) / (double) period;
                    double k = ledPhase < 0.1 ? ledPhase / 0.1 : Math.exp(-(ledPhase - 0.1) * 6.0);
                    int c = scale(palette[i % palette.length], 0.04 + 0.96 * k);
                    out[i] = k > 0.8 ? mix(c, 0xFFFFFFFF, (k - 0.8) / 0.2 * 0.6) : c;
                }
                break;
            }

            case "candle": {
                // smoothed value noise: one shared flame plus a little independent flicker per LED
                long seg = Math.max(40, speed / 6);
                long step = t / seg;
                double f = (t % seg) / (double) seg;
                double ease = f * f * (3 - 2 * f);
                double shared = noise(step, n) + (noise(step + 1, n) - noise(step, n)) * ease;
                for (int i = 0; i < n; i++) {
                    double own = noise(step, i) + (noise(step + 1, i) - noise(step, i)) * ease;
                    out[i] = scale(palette[i % palette.length], 0.4 + 0.6 * (0.65 * shared + 0.35 * own));
                }
                break;
            }

            case "random": {
                long interval = Math.max(120, cfg.optLong("randomIntervalMs", 1500));
                boolean perLed = cfg.optBoolean("randomPerLed", true);
                boolean smooth = cfg.optBoolean("randomSmooth", true);
                if (randFrom == null || randFrom.length != n || t - randStart >= randDuration) {
                    randFrom = (randTo != null && randTo.length == n) ? randTo : randomColors(n, perLed, cfg);
                    randTo = randomColors(n, perLed, cfg);
                    randStart = t;
                    randDuration = interval;
                }
                double k = smooth ? clamp01((t - randStart) / (double) randDuration) : 0;
                for (int i = 0; i < n; i++) out[i] = mix(randFrom[i], randTo[i], k);
                break;
            }

            case "custom": {
                long rotateMs = cfg.optLong("rotateMs", 0);
                int shift = rotateMs > 50 ? (int) ((t / rotateMs) % n) : 0;
                for (int i = 0; i < n; i++) out[i] = palette[((i + shift) % n) % palette.length];
                break;
            }

            default:
                for (int i = 0; i < n; i++) out[i] = palette[i % palette.length];
        }

        if (bright < 1.0) for (int i = 0; i < n; i++) out[i] = scale(out[i], bright);
        return out;
    }

    private int[] randomColors(int n, boolean perLed, JSONObject cfg) {
        float sat = (float) clamp01(cfg.optDouble("randomSaturation", 1.0));
        int[] c = new int[n];
        if (perLed) {
            for (int i = 0; i < n; i++) c[i] = hsv(rnd.nextInt(360), sat, 1f);
        } else {
            int one = hsv(rnd.nextInt(360), sat, 1f);
            for (int i = 0; i < n; i++) c[i] = one;
        }
        return c;
    }

    private static int[] colors(JSONObject cfg) {
        JSONArray a = cfg.optJSONArray("colors");
        if (a != null && a.length() > 0) {
            int[] c = new int[a.length()];
            for (int i = 0; i < a.length(); i++) c[i] = (int) (a.optLong(i, 0xFFFFFFFFL) | 0xFF000000L);
            return c;
        }
        return new int[]{(int) (cfg.optLong("color", 0xFFFFFFFFL) | 0xFF000000L)};
    }

    // ------------------------------------------------------------------------------ colour maths

    static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    static int scale(int color, double k) {
        k = clamp01(k);
        int r = (int) (((color >> 16) & 0xFF) * k);
        int g = (int) (((color >> 8) & 0xFF) * k);
        int b = (int) ((color & 0xFF) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static int mix(int a, int b, double k) {
        k = clamp01(k);
        int r = (int) (((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
        int g = (int) (((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
        int bl = (int) ((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * Deterministic value noise in [0, 1) for one time step and lane.
     *
     * The preview mirrors this bit for bit, so the flicker on screen is the flicker on the LEDs.
     */
    static double noise(long step, int lane) {
        long x = step * 0x2545F4914F6CDD1DL + lane * 0x5851F42D4C957F2DL;
        x ^= x >>> 31;
        x *= 0x27BB2EE687B0B0FDL;
        x ^= x >>> 29;
        return (x >>> 11) / (double) (1L << 53);
    }

    static int hsv(double h, float s, float v) {
        double c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c;
        double r, g, b;
        switch ((int) (h / 60) % 6) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x;
        }
        return 0xFF000000
                | ((int) ((r + m) * 255) << 16)
                | ((int) ((g + m) * 255) << 8)
                | (int) ((b + m) * 255);
    }
}
