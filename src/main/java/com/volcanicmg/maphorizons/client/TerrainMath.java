package com.volcanicmg.maphorizons.client;

/**
 * Pure column and colour maths for painting DH terrain. Columns are parallel arrays of DH data points
 * (top/bottom Y, air flag); order is not trusted. Colours are 0xRRGGBB until {@link #pack}.
 */
public final class TerrainMath {
    private TerrainMath() {
    }

    /**
     * Point indices sorted by top Y, highest first. Computed once per column and shared by
     * {@link #pickSurface} and {@link #liquidDepth}; columns are a handful of points, so insertion sort.
     */
    public static int[] byTopDescending(int[] top) {
        int[] order = new int[top.length];
        for (int i = 0; i < order.length; i++) {
            int j = i;
            while (j > 0 && top[order[j - 1]] < top[i]) {
                order[j] = order[j - 1];
                j--;
            }
            order[j] = i;
        }
        return order;
    }

    /**
     * Picks the point to paint in a column. Y values are relative to the dimension's minY and {@code top} is
     * exclusive, as DH stores them: a point covers {@code bottom..top-1}.
     *
     * @param order     {@link #byTopDescending} of {@code top}
     * @param ceiling   true for dimensions with a roof (Nether): skip the top solid run if it reaches the
     *                  ceiling and take the highest non-air point below the first gap under it
     * @param ceilingY  relative Y of the top block of the logical height ({@code logicalHeight - 1})
     * @return index into the arrays, or -1 if the column is all air
     */
    public static int pickSurface(int[] order, int[] top, int[] bottom, boolean[] air, boolean ceiling, int ceilingY) {
        int first = -1;
        // Above the roof (builds on top of the Nether) is out of scope in ceiling dimensions.
        for (int i : order) if (!air[i] && !(ceiling && bottom[i] > ceilingY)) { first = i; break; }
        if (first < 0 || !ceiling || top[first] - 1 < ceilingY) return first;
        // Walk down the roof run until there is a gap (an air point or a missing Y range).
        int runBottom = bottom[first];
        boolean gap = false;
        for (int i : order) {
            if (top[i] > top[first] || i == first) continue;
            if (!gap) {
                if (air[i] || top[i] < runBottom) gap = true;
                else {
                    runBottom = Math.min(runBottom, bottom[i]);
                    continue;
                }
            }
            if (!air[i]) return i;
        }
        return first;
    }

    /** @return the first solid (non-air, non-liquid) point below {@code idx}, or -1 */
    public static int floorBelow(int[] order, int[] top, boolean[] air, boolean[] liquid, int idx) {
        boolean below = false;
        for (int i : order) {
            if (i == idx) { below = true; continue; }
            if (below && !air[i] && !liquid[i] && top[i] < top[idx]) return i;
        }
        return -1;
    }

    /** @return blocks of liquid above the next solid point below {@code idx}, at least 1 */
    public static int liquidDepth(int[] order, int[] top, int[] bottom, boolean[] air, boolean[] liquid, int idx) {
        int floor = floorBelow(order, top, air, liquid, idx);
        return Math.max(1, floor >= 0 ? top[idx] - top[floor] : top[idx] - bottom[idx]);
    }

    /**
     * See-through water like Xaero's: the floor shows through shallow water, deep water is mostly water colour.
     *
     * @return water blended over the floor colour
     */
    public static int blendWater(int water, int floor, int depth) {
        double a = Math.min(0.9, 0.45 + depth * 0.05);
        int r = (int) Math.round((water >> 16 & 0xFF) * a + (floor >> 16 & 0xFF) * (1 - a));
        int g = (int) Math.round((water >> 8 & 0xFF) * a + (floor >> 8 & 0xFF) * (1 - a));
        int b = (int) Math.round((water & 0xFF) * a + (floor & 0xFF) * (1 - a));
        return r << 16 | g << 8 | b;
    }

    /** Multiplies a texture colour by a biome tint, as Xaero does. */
    public static int tint(int rgb, int tint) {
        int r = (rgb >> 16 & 0xFF) * (tint >> 16 & 0xFF) / 255;
        int g = (rgb >> 8 & 0xFF) * (tint >> 8 & 0xFF) / 255;
        int b = (rgb & 0xFF) * (tint & 0xFF) / 255;
        return r << 16 | g << 8 | b;
    }

    /** Darker with depth, like vanilla maps; floored so deep oceans stay readable. */
    public static int darkenLiquid(int rgb, int depth) {
        return scale(rgb, Math.max(0.55, 1.0 - depth * 0.03));
    }

    /**
     * Per-pixel slope shading like Xaero's: brighter when higher than the north and west neighbours, darker when
     * lower. One block of rise against both is about +16% at strength 1.
     */
    public static int shade(int rgb, int h, int hNorth, int hWest, double strength) {
        int d = Math.max(-4, Math.min(4, (h - hNorth) + (h - hWest)));
        return scale(rgb, 1.0 + d * 0.08 * strength);
    }

    static int scale(int rgb, double f) {
        int r = clamp((int) Math.round((rgb >> 16 & 0xFF) * f));
        int g = clamp((int) Math.round((rgb >> 8 & 0xFF) * f));
        int b = clamp((int) Math.round((rgb & 0xFF) * f));
        return r << 16 | g << 8 | b;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** Xaero highlight packing: {@code B<<24 | G<<16 | R<<8 | A}. */
    public static int pack(int rgb, int alpha) {
        return (rgb & 0xFF) << 24 | (rgb >> 8 & 0xFF) << 16 | (rgb >> 16 & 0xFF) << 8 | alpha;
    }
}
