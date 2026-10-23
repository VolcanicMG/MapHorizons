package com.volcanicmg.maphorizons.share;

import java.util.ArrayList;
import java.util.List;

/** Pure helpers for terrain sharing. */
public final class ShareMath {
    private ShareMath() {
    }

    /** @return data, or an empty array ("missing") if it would not fit in one packet of {@code maxBytes} */
    public static byte[] fitOrMissing(byte[] data, int maxBytes) {
        return data.length > maxBytes - 64 ? new byte[0] : data;
    }

    /**
     * Splits items into consecutive groups whose total size stays within {@code max}; an item larger than
     * {@code max} goes alone.
     *
     * @return group end indices (exclusive), in order
     */
    public static List<Integer> splitBySize(int[] sizes, int max) {
        List<Integer> ends = new ArrayList<>();
        long sum = 0;
        for (int i = 0; i < sizes.length; i++) {
            if (sum > 0 && sum + sizes[i] > max) {
                ends.add(i);
                sum = 0;
            }
            sum += sizes[i];
        }
        if (sizes.length > 0) ends.add(sizes.length);
        return ends;
    }
}
