package com.volcanicmg.maphorizons;

/**
 * Explored-chunk bitsets: one 32x32-chunk region is 1024 bits in {@code long[16]}, keyed by a packed region long.
 * Pure Java so the runnable check can exercise it without Minecraft.
 */
public final class Bits {
    public static final int WORDS = 16;

    private Bits() {
    }

    /** @return region key for chunk coords, same packing as {@code ChunkPos.asLong(cx >> 5, cz >> 5)} */
    public static long regionKey(int cx, int cz) {
        return pack(cx >> 5, cz >> 5);
    }

    public static long pack(int x, int z) {
        return x & 0xFFFFFFFFL | (z & 0xFFFFFFFFL) << 32;
    }

    public static int unpackX(long key) {
        return (int) key;
    }

    public static int unpackZ(long key) {
        return (int) (key >>> 32);
    }

    private static int bit(int cx, int cz) {
        return (cz & 31) << 5 | (cx & 31);
    }

    /** Sets the chunk's bit. @return true if it was not set before */
    public static boolean set(long[] words, int cx, int cz) {
        int b = bit(cx, cz);
        long mask = 1L << (b & 63);
        if ((words[b >> 6] & mask) != 0) return false;
        words[b >> 6] |= mask;
        return true;
    }

    public static boolean get(long[] words, int cx, int cz) {
        if (words == null) return false;
        int b = bit(cx, cz);
        return (words[b >> 6] & 1L << (b & 63)) != 0;
    }

    /** ORs {@code src} into {@code dst}; null src is a no-op. */
    public static void or(long[] dst, long[] src) {
        if (src == null) return;
        for (int i = 0; i < WORDS; i++) dst[i] |= src[i];
    }

    /** DH leaf sections (section detail 6) are 64x64-block tiles; a 512-block region holds 8x8 of them. */
    public static long tileRegionKey(int tx, int tz) {
        return pack(Math.floorDiv(tx, 8), Math.floorDiv(tz, 8));
    }

    /** @return the tile's bit in its region's 64-bit mask */
    public static long tileBit(int tx, int tz) {
        return 1L << (Math.floorMod(tz, 8) * 8 + Math.floorMod(tx, 8));
    }

    /** @return true if any of the 4x4 chunks of DH leaf tile (tx, tz) is set in its region's bitset */
    public static boolean anyInTile(long[] words, int tx, int tz) {
        if (words == null) return false;
        for (int dz = 0; dz < 4; dz++)
            for (int dx = 0; dx < 4; dx++)
                if (get(words, tx * 4 + dx, tz * 4 + dz)) return true;
        return false;
    }

    public static boolean any(long[] words) {
        if (words == null) return false;
        for (long w : words) if (w != 0) return true;
        return false;
    }
}
