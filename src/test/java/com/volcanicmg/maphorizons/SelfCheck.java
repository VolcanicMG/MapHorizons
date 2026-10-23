package com.volcanicmg.maphorizons;

import com.volcanicmg.maphorizons.client.TerrainMath;

/** The one runnable check ({@code ./gradlew selfCheck}, also run by {@code check}): bitsets and column maths. */
public final class SelfCheck {
    public static void main(String[] args) {
        bits();
        surface();
        nether();
        colour();
        System.out.println("MapHorizons self-check passed");
    }

    private static void bits() {
        long[] a = new long[Bits.WORDS], b = new long[Bits.WORDS];
        check(Bits.set(a, 5, 7), "first set is new");
        check(!Bits.set(a, 5, 7), "second set is not new");
        check(Bits.get(a, 5, 7) && !Bits.get(a, 7, 5), "get reads the right bit");
        // Negative coords land in the same region slot as their positive mod-32 twin.
        check(Bits.regionKey(-1, -1) == Bits.pack(-1, -1), "negative region key");
        Bits.set(b, -1, -33);
        check(Bits.get(b, 31, 31) && Bits.get(b, -1, -33), "negative chunk bit");
        Bits.or(a, b);
        check(Bits.get(a, 5, 7) && Bits.get(a, 31, 31), "or keeps both");
        check(!Bits.get(null, 0, 0) && !Bits.any(new long[Bits.WORDS]) && Bits.any(a), "null/empty masks");
        check(Bits.unpackX(Bits.pack(-3, 9)) == -3 && Bits.unpackZ(Bits.pack(-3, 9)) == 9, "pack round-trip");
        // DH leaf tiles (64 blocks) to Xaero regions (512 blocks): 8x8 tiles, bit = z * 8 + x.
        check(Bits.tileRegionKey(8, 0) == Bits.pack(1, 0) && Bits.tileBit(8, 0) == 1L, "tile 8 starts region 1");
        check(Bits.tileRegionKey(-1, -1) == Bits.pack(-1, -1) && Bits.tileBit(-1, -1) == 1L << 63, "tile -1 is last bit");
        // Chunk to tile is >> 2 (4 chunks per tile); chunk 31 sits in tile 7, region 0.
        check(Bits.tileRegionKey(31 >> 2, 0) == Bits.pack(0, 0) && Bits.tileBit(31 >> 2, 0) == 1L << 7, "chunk to tile");
    }

    // DH layout: Y relative to the dimension's minY, top exclusive (a point covers bottom..top-1).
    private static int pick(int[] top, int[] bottom, boolean[] air, boolean ceiling, int ceilingY) {
        return TerrainMath.pickSurface(TerrainMath.byTopDescending(top), top, bottom, air, ceiling, ceilingY);
    }

    private static int depth(int[] top, int[] bottom, boolean[] air, boolean[] liquid, int idx) {
        return TerrainMath.liquidDepth(TerrainMath.byTopDescending(top), top, bottom, air, liquid, idx);
    }

    private static void surface() {
        // Points out of order: stone 0..60, water 61..64, air 65..319.
        int[] top = {320, 61, 65}, bottom = {65, 0, 61};
        boolean[] air = {true, false, false}, liquid = {false, false, true};
        int s = pick(top, bottom, air, false, 0);
        check(s == 2, "highest non-air is the water");
        check(depth(top, bottom, air, liquid, s) == 4, "water depth to stone");
        check(TerrainMath.floorBelow(TerrainMath.byTopDescending(top), top, air, liquid, s) == 1, "floor under water");
        check(pick(new int[]{100}, new int[]{0}, new boolean[]{true}, false, 0) == -1, "all air");
        check(java.util.Arrays.equals(TerrainMath.byTopDescending(new int[]{5, 9, 1, 7}), new int[]{1, 3, 0, 2}),
                "order by top");
    }

    private static void nether() {
        int ceilingY = 127; // logicalHeight 128 - 1, relative to minY 0
        // Bedrock roof 123..127, netherrack 100..122, air 40..99, netherrack floor 0..39.
        int[] top = {128, 123, 100, 40}, bottom = {123, 100, 40, 0};
        boolean[] air = {false, false, true, false};
        check(pick(top, bottom, air, true, ceilingY) == 3, "nether floor under roof");
        check(pick(top, bottom, air, false, ceilingY) == 0, "no ceiling rule = roof");
        // Lava lake: roof, air, lava 28..31, netherrack below; plus a block built above the roof (ignored).
        int[] lTop = {141, 128, 100, 32, 28}, lBottom = {140, 123, 32, 28, 0};
        boolean[] lAir = {false, false, true, false, false}, lLiquid = {false, false, false, true, false};
        int lava = pick(lTop, lBottom, lAir, true, ceilingY);
        check(lava == 3, "lava lake is the floor");
        check(depth(lTop, lBottom, lAir, lLiquid, lava) == 4, "lava depth darkens like water");
        // Solid all the way down: fall back to the topmost point.
        check(pick(new int[]{128, 101}, new int[]{101, 0}, new boolean[]{false, false}, true, ceilingY) == 0,
                "no gap falls back to top");
        // Hole in the roof: top run does not reach the ceiling, so it is the surface.
        check(pick(new int[]{127, 61}, new int[]{61, 0}, new boolean[]{true, false}, true, ceilingY) == 1,
                "open column");
        // Ceiling dimension with minY -64, logicalHeight 192: relative ceilingY 191, absolute roof at Y 127.
        int[] mTop = {192, 186, 100}, mBottom = {186, 100, 0};
        boolean[] mAir = {false, true, false};
        check(pick(mTop, mBottom, mAir, true, 191) == 2, "minY != 0 ceiling uses relative heights");
    }

    private static void colour() {
        check(TerrainMath.pack(0x112233, 255) == (0x33 << 24 | 0x22 << 16 | 0x11 << 8 | 0xFF), "xaero packing");
        int base = 0x808080;
        check((TerrainMath.shade(base, 70, 64, 64, 1.0) & 0xFF) > 0x80, "higher than neighbours is brighter");
        check((TerrainMath.shade(base, 60, 64, 64, 1.0) & 0xFF) < 0x80, "lower is darker");
        check(TerrainMath.shade(base, 70, 64, 64, 0.0) == base, "zero strength is flat");
        // One block of rise against both neighbours is a visible step, like Xaero's slopes (~+16%).
        check((TerrainMath.shade(base, 65, 64, 64, 1.0) & 0xFF) >= 0x80 * 115 / 100, "slope step is visible");
        check((TerrainMath.darkenLiquid(0x0000FF, 10) & 0xFF) < 0xFF, "deeper liquid is darker");
        // See-through water: shallow shows more floor (red here) than deep.
        int shallow = TerrainMath.blendWater(0x0000FF, 0xFF0000, 1), deep = TerrainMath.blendWater(0x0000FF, 0xFF0000, 20);
        check((shallow >> 16 & 0xFF) > (deep >> 16 & 0xFF) && (deep >> 16 & 0xFF) > 0, "water depth blend");
        check(TerrainMath.tint(0xFFFFFF, 0x7FB238) == 0x7FB238 && TerrainMath.tint(0x808080, 0xFFFFFF) == 0x808080,
                "texture times tint");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
