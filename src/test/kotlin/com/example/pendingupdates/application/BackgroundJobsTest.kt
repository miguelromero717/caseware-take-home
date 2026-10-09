package com.example.pendingupdates.application

import com.example.pendingupdates.ENG
import com.example.pendingupdates.FIRM
import com.example.pendingupdates.MutableClock
import com.example.pendingupdates.OTHER_FIRM
import com.example.pendingupdates.TPL
import com.example.pendingupdates.domain.EngagementId
import com.example.pendingupdates.infrastructure.FakeEngagementStore
import com.example.pendingupdates.infrastructure.InMemoryStateRepository
import com.example.pendingupdates.infrastructure.InMemoryTemplateCatalog
import com.example.pendingupdates.infrastructure.RecordingMetrics
import com.example.pendingupdates.ports.TemplateRef
import com.example.pendingupdates.state
import com.example.pendingupdates.v
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackgroundJobsTest {
    private val states = InMemoryStateRepository()
    private val engagements = FakeEngagementStore()
    private val metrics = RecordingMetrics()
    private val clock = MutableClock()

    @Test
    fun `backfill creates missing rows and never overwrites existing ones`() {
        val other = EngagementId("eng-99")
        engagements.templateRefs[FIRM to ENG] = TemplateRef(TPL, v(3))
        engagements.templateRefs[FIRM to other] = TemplateRef(TPL, v(2))
        // The engagement system already wrote a row for ENG (e.g. the user applied v6 meanwhile).
        states.save(FIRM, state(6))

        val result = BackfillJob(engagements, states).run(FIRM)

        assertEquals(BackfillJob.Result(inserted = 1, skipped = 1), result)
        assertEquals(state(6), states.find(FIRM, ENG))
        assertEquals(v(2), states.find(FIRM, other)?.appliedVersion)
        assertEquals(1, engagements.slowLoads, "Only the missing engagement is loaded")
    }

    @Test
    fun `backfill is resumable`() {
        engagements.templateRefs[FIRM to ENG] = TemplateRef(TPL, v(3))
        val job = BackfillJob(engagements, states)

        job.run(FIRM)
        val second = job.run(FIRM)

        assertEquals(BackfillJob.Result(inserted = 0, skipped = 1), second)
        assertEquals(1, engagements.slowLoads)
    }

    @Test
    fun `reconciliation on open repairs drift and alerts`() {
        states.save(FIRM, state(4))
        val reconciler = Reconciler(states, metrics, clock)

        assertEquals(Reconciler.Outcome.REPAIRED, reconciler.verifyOnOpen(FIRM, ENG, actualVersion = v(6)))
        assertEquals(listOf(ENG), metrics.drifts)
        assertEquals(v(6), states.find(FIRM, ENG)?.appliedVersion)

        assertEquals(Reconciler.Outcome.MATCH, reconciler.verifyOnOpen(FIRM, ENG, actualVersion = v(6)))
        assertEquals(1, metrics.drifts.size)
    }

    @Test
    fun `metrics job reports counts per firm and a heartbeat`() {
        val catalog = InMemoryTemplateCatalog().apply { publish(TPL, v(6)) }
        states.save(FIRM, state(4))
        states.save(FIRM, state(6).copy(engagementId = EngagementId("eng-up-to-date")))
        states.save(FIRM, state(2, archived = true).copy(engagementId = EngagementId("eng-archived")))
        states.save(OTHER_FIRM, state(1))

        MetricsJob(states, catalog, metrics).run(FIRM)

        assertEquals(listOf(Triple(FIRM, TPL, 1)), metrics.affected)
        assertEquals(listOf(FIRM), metrics.heartbeats)
        assertTrue(metrics.affected.none { it.first == OTHER_FIRM }, "Firms are counted independently")
    }
}
