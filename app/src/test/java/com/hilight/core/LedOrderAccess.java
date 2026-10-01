package com.hilight.core;

import org.json.JSONArray;

/** Test-only bridge to the package-private {@link LedOrder}, for Kotlin tests in another package. */
public final class LedOrderAccess {
    private LedOrderAccess() {}

    public static int[] parse(JSONArray array) {
        return LedOrder.parse(array);
    }

    public static int[] apply(int[] frame, int[] order) {
        return LedOrder.apply(frame, order);
    }
}
