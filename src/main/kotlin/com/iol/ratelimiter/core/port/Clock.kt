package com.iol.ratelimiter.core.port

fun interface Clock {
    fun nowMillis(): Long
}
