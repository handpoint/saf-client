package com.handpoint.safclient.testutil

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A UTC [Clock] that a test moves forward by hand. */
class MutableClock(private var now: Instant) : Clock() {
    fun advance(by: Duration) {
        now += by
    }

    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
