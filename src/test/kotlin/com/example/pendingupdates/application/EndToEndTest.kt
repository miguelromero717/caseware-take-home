package com.example.pendingupdates.application

import com.example.pendingupdates.ENG
import com.example.pendingupdates.FIRM
import com.example.pendingupdates.MutableClock
import com.example.pendingupdates.TPL
import com.example.pendingupdates.domain.DecisionCommand
import com.example.pendingupdates.domain.DecisionKind
import com.example.pendingupdates.domain.PendingStatus
import com.example.pendingupdates.domain.UserId
import com.example.pendingupdates.infrastructure.FakeEngagementStore
import com.example.pendingupdates.infrastructure.InMemoryDecisionLog
import com.example.pendingupdates.infrastructure.InMemoryStateRepository
import com.example.pendingupdates.infrastructure.InMemoryTemplateCatalog
import com.example.pendingupdates.infrastructure.InMemoryTransactions
import com.example.pendingupdates.ports.TemplateRef
import com.example.pendingupdates.v
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The "engagement 17" walkthrough from the design doc, step by step. */
class EndToEndTest {

    @Test
    fun `create, publish, decline, publish again, apply`() {
        val states = InMemoryStateRepository()
        val log = InMemoryDecisionLog()
        val engagements = FakeEngagementStore()
        val catalog = InMemoryTemplateCatalog()
        val hooks = EngagementLifecycleHooks(states)
        val list = EngagementListService(states, catalog)
        val decisions = DecisionService(InMemoryTransactions(states, log), states, log, engagements, catalog, MutableClock())
        fun decide(kind: DecisionKind, target: Int) =
            decisions.decide(DecisionCommand(FIRM, ENG, kind, v(target), UserId("auditor-1"), "hash"))
        fun status() = list.list(FIRM).single().status

        // Engagement created on v4 (row written in the engagement system's transaction).
        catalog.publish(TPL, v(4))
        engagements.templateRefs[FIRM to ENG] = TemplateRef(TPL, v(4))
        hooks.onCreated(FIRM, ENG, TPL, v(4))
        assertEquals(PendingStatus.UpToDate, status())

        // v5 published: nothing is written to the firm, the list just sees it.
        catalog.publish(TPL, v(5))
        assertIs<PendingStatus.Pending>(status())

        // User declines v5.
        decide(DecisionKind.DECLINE, 5)
        assertEquals(PendingStatus.UpToDate, status())

        // v6 published: summary runs from v4, with v5 marked as previously declined.
        catalog.publish(TPL, v(6))
        val pending = status()
        assertIs<PendingStatus.Pending>(pending)
        assertEquals(v(4) to v(6), pending.diffFrom to pending.diffTo)
        assertEquals(listOf(true, false), pending.versions.map { it.previouslyDeclined })

        // User applies v6.
        decide(DecisionKind.APPLY, 6)
        assertEquals(PendingStatus.UpToDate, status())
        assertEquals(2, log.history(FIRM, ENG).size)
        assertEquals(0, engagements.slowLoads, "No engagement was ever loaded to answer what's pending")
    }
}
