package com.onyx.browser.download

import kotlinx.coroutines.delay

/**
 * Token-Bucket rate limiter for bandwidth allocation and download speed throttling.
 * Allows smooth rate limiting across concurrent chunk threads.
 */
class TokenBucketLimiter(
    @Volatile private var bytesPerSecond: Long = 0L // 0 = unlimited
) {
    private var availableTokens: Double = 0.0
    private var lastRefillTime: Long = System.nanoTime()
    private val lock = Any()

    fun setLimit(limitBytesPerSec: Long) {
        synchronized(lock) {
            bytesPerSecond = limitBytesPerSec
            availableTokens = limitBytesPerSec.toDouble()
            lastRefillTime = System.nanoTime()
        }
    }

    fun getLimit(): Long = bytesPerSecond

    suspend fun acquire(bytes: Int) {
        val rate = bytesPerSecond
        if (rate <= 0L || bytes <= 0) return

        var waitTimeMs = 0L
        synchronized(lock) {
            val now = System.nanoTime()
            val elapsedSec = (now - lastRefillTime).coerceAtLeast(0L) / 1_000_000_000.0
            lastRefillTime = now

            // Refill tokens up to maximum 2-second burst capacity
            availableTokens = (availableTokens + elapsedSec * rate).coerceAtMost(rate.toDouble() * 2.0)
            availableTokens -= bytes

            if (availableTokens < 0.0) {
                // Deficit in tokens; calculate sleep duration
                val deficit = -availableTokens
                waitTimeMs = ((deficit / rate) * 1000.0).toLong().coerceIn(5L, 2000L)
            }
        }

        if (waitTimeMs > 0L) {
            delay(waitTimeMs)
        }
    }
}
