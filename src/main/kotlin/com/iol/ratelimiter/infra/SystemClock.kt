package com.iol.ratelimiter.infra

import com.iol.ratelimiter.core.port.Clock

object SystemClock : Clock {
    private const val NANOS_PER_MILLI = 1_000_000L

    // nanoTime is monotonic — immune to NTP backward steps and large forward wall-clock jumps.
    // Safe to use for rate limiting because all bucket state lives in-memory (same JVM session);
    // elapsed time is always computed as a nanoTime delta, never as an absolute timestamp.
    override fun nowMillis() = System.nanoTime() / NANOS_PER_MILLI
}
