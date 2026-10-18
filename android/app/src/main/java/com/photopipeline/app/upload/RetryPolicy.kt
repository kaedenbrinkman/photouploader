package com.photopipeline.app.upload

import kotlin.math.min
import kotlin.random.Random

object RetryPolicy {
    fun backoffMs(attempt: Int, baseMs: Long = 1_000L, capMs: Long = 60_000L): Long {
        val exp = min(capMs, baseMs * (1L shl attempt.coerceAtMost(6)))
        val jitter = Random.nextLong(0, exp / 4 + 1)
        return exp + jitter
    }
}
