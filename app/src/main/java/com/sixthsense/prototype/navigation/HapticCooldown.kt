package com.sixthsense.prototype.navigation

/** Pure rate limiter: one command per configured interval, with no backlog. */
class HapticCooldown(private val cooldownMillis: Long) {
    private var lastSentAtMillis: Long? = null

    fun canSend(nowMillis: Long): Boolean = lastSentAtMillis?.let { nowMillis - it >= cooldownMillis } ?: true

    fun markSent(nowMillis: Long) { lastSentAtMillis = nowMillis }
}
