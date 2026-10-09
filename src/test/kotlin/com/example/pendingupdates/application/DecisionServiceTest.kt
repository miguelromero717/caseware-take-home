package com.example.pendingupdates.application

import com.example.pendingupdates.ENG
import com.example.pendingupdates.FIRM
import com.example.pendingupdates.MutableClock
import com.example.pendingupdates.TPL
import com.example.pendingupdates.domain.DecisionCommand
import com.example.pendingupdates.domain.DecisionKind
import com.example.pendingupdates.domain.DecisionResult
import com.example.pendingupdates.domain.UserId
import com.example.pendingupdates.infrastructure.FakeEngagementStore
import com.example.pendingupdates.infrastructure.InMemoryDecisionLog
import com.example.pendingupdates.infrastructure.InMemoryStateRepository
import com.example.pendingupdates.infrastructure.InMemoryTemplateCatalog
import com.example.pendingupdates.infrastructure.InMemoryTransactions
import com.example.pendingupdates.ports.TemplateRef
import com.example.pendingupdates.state
import com.example.pendingupdates.v
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DecisionServiceTest {
    private val states = InMemoryStateRepository()
    private val log = InMemoryDecisionLog()
    private val engagements = FakeEngagementStore()
    private val catalog = InMemoryTemplateCatalog()
    private val service = DecisionService(InMemoryTransactions(states, log), states, log, engagements, catalog, MutableClock())

    private fun command(kind: DecisionKind, target: Int) =
        DecisionCommand(FIRM, ENG, kind, v(target), UserId("auditor-1"), summaryHash = "abc123")

    private fun given(applied: Int, acknowledged: Int = applied, latest: Int) {
        states.save(FIRM, state(applied, acknowledged))
        engagements.templateRefs[FIRM to ENG] = TemplateRef(TPL, v(applied))
        catalog.publish(TPL, v(latest))
    }

    @Test
    fun `apply updates the engagement, the row and the log together`() {
        given(applied = 4, latest = 6)

        val result = service.decide(command(DecisionKind.APPLY, 6))

        assertIs<DecisionResult.Accepted>(result)
        assertEquals(state(6), states.find(FIRM, ENG))
        assertEquals(v(6), engagements.templateRefs.getValue(FIRM to ENG).version)
        val entry = log.history(FIRM, ENG).single()
        assertEquals(v(4) to v(6), entry.fromVersion to entry.toVersion)
        assertEquals("abc123", entry.summaryHash)
    }

    @Test
    fun `decline records the decision without touching content`() {
        given(applied = 4, latest = 5)

        service.decide(command(DecisionKind.DECLINE, 5))

        assertEquals(state(applied = 4, acknowledged = 5), states.find(FIRM, ENG))
        assertEquals(v(4), engagements.templateRefs.getValue(FIRM to ENG).version)
    }

    @Test
    fun `decision on an outdated summary is stale and changes nothing`() {
        given(applied = 4, latest = 7)

        val result = service.decide(command(DecisionKind.APPLY, 6))

        assertEquals(DecisionResult.Stale(latest = v(7)), result)
        assertEquals(state(4), states.find(FIRM, ENG))
        assertTrue(log.history(FIRM).isEmpty())
    }

    @Test
    fun `a failure while applying rolls back the row and the log`() {
        given(applied = 4, latest = 6)
        engagements.failNextApply = true

        assertFailsWith<IllegalStateException> { service.decide(command(DecisionKind.APPLY, 6)) }

        assertEquals(state(4), states.find(FIRM, ENG))
        assertTrue(log.history(FIRM).isEmpty())
    }
}
