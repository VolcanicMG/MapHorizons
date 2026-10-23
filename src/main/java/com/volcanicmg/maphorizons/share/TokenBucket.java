package com.volcanicmg.maphorizons.share;

/** Byte-rate limiter: refills {@code ratePerSecond} bytes per second, holds at most one second of burst. */
public final class TokenBucket {
    private final long ratePerSecond;
    private double tokens;
    private long lastMs;

    public TokenBucket(long ratePerSecond, long nowMs) {
        this.ratePerSecond = ratePerSecond;
        this.tokens = ratePerSecond;
        this.lastMs = nowMs;
    }

    private void refill(long nowMs) {
        tokens = Math.min(ratePerSecond, tokens + (nowMs - lastMs) * ratePerSecond / 1000.0);
        lastMs = nowMs;
    }

    /** @return true if sending may start now (tokens left); actual size is charged afterwards */
    public boolean ready(long nowMs) {
        refill(nowMs);
        return tokens > 0;
    }

    /** Charges bytes already sent; may go negative, which delays the next {@link #ready}. */
    public void charge(long bytes, long nowMs) {
        refill(nowMs);
        tokens -= bytes;
    }
}
