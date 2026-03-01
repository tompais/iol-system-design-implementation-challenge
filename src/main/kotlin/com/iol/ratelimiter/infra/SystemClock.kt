package com.iol.ratelimiter.infra

import com.iol.ratelimiter.core.port.Clock

object SystemClock : Clock {
    override fun nowMillis() = System.currentTimeMillis()
}
