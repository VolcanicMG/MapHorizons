package com.volcanicmg.maphorizons.client;

import java.util.concurrent.atomic.AtomicLong;

/** Counters for the one-time "why is nothing painted" log line. Any thread. */
public final class Diagnostics {
    public static final AtomicLong REGION_QUERIES = new AtomicLong();
    public static final AtomicLong REGION_NO_DH_DATA = new AtomicLong();
    public static final AtomicLong REGION_NOT_ALLOWED = new AtomicLong();
    public static final AtomicLong CHUNK_COLOUR_CALLS = new AtomicLong();
    public static final AtomicLong REGIONS_COMPUTED = new AtomicLong();

    private Diagnostics() {
    }

    public static void reset() {
        REGION_QUERIES.set(0);
        REGION_NO_DH_DATA.set(0);
        REGION_NOT_ALLOWED.set(0);
        CHUNK_COLOUR_CALLS.set(0);
        REGIONS_COMPUTED.set(0);
    }

    public static String summary() {
        return "Xaero region queries=" + REGION_QUERIES.get() + " (no DH data=" + REGION_NO_DH_DATA.get()
                + ", not allowed=" + REGION_NOT_ALLOWED.get() + "), chunk colour calls=" + CHUNK_COLOUR_CALLS.get()
                + ", regions computed=" + REGIONS_COMPUTED.get();
    }
}
