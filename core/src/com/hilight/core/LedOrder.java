package com.hilight.core;

import org.json.JSONArray;

/**
 * Maps the renderer's logical LED positions onto the physical array.
 *
 * <p>Every pattern is drawn in logical order: position 0 at the top of the ring and then clockwise
 * as seen looking at the back of the phone. The hardware's own LED numbering has not been confirmed
 * to follow that ring, so the app's "Map your LEDs" wizard records, for each logical position, which
 * hardware LED sits there, and sends it as {@code ledOrder}. Anything that is not a complete
 * permutation is ignored, which leaves output exactly as it was before mapping existed.</p>
 */
final class LedOrder {

    private LedOrder() {}

    /** A validated order, or null for "unmapped" (missing, malformed, or the identity). */
    static int[] parse(JSONArray array) {
        if (array == null) return null;
        int n = array.length();
        if (n < 2 || n > 64) return null;
        int[] order = new int[n];
        boolean[] seen = new boolean[n];
        boolean identity = true;
        for (int slot = 0; slot < n; slot++) {
            int led = array.optInt(slot, -1);
            if (led < 0 || led >= n || seen[led]) return null;
            seen[led] = true;
            order[slot] = led;
            if (led != slot) identity = false;
        }
        return identity ? null : order;
    }

    /** Moves each logical position's colour to its hardware LED; unchanged when unmapped or mismatched. */
    static int[] apply(int[] frame, int[] order) {
        if (order == null || frame == null || order.length != frame.length) return frame;
        int[] out = new int[frame.length];
        for (int slot = 0; slot < frame.length; slot++) out[order[slot]] = frame[slot];
        return out;
    }
}
