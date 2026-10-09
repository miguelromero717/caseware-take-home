package com.example.pendingupdates

import com.example.pendingupdates.domain.EngagementId
import com.example.pendingupdates.domain.EngagementTemplateState
import com.example.pendingupdates.domain.FirmId
import com.example.pendingupdates.domain.TemplateId
import com.example.pendingupdates.domain.TemplateVersion
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

val FIRM = FirmId("firm-a")
val OTHER_FIRM = FirmId("firm-b")
val ENG = EngagementId("eng-17")
val TPL = TemplateId("audit-2026")

fun v(n: Int) = TemplateVersion(n)

fun state(applied: Int, acknowledged: Int = applied, archived: Boolean = false) =
    EngagementTemplateState(ENG, TPL, v(applied), v(acknowledged), archived)

class MutableClock(private var now: Instant = Instant.parse("2026-10-08T12:00:00Z")) : Clock() {
    fun advance(by: Duration) { now = now.plus(by) }
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}
