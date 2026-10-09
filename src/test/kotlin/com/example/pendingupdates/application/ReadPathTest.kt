package com.example.pendingupdates.application

import com.example.pendingupdates.ENG
import com.example.pendingupdates.FIRM
import com.example.pendingupdates.MutableClock
import com.example.pendingupdates.TPL
import com.example.pendingupdates.domain.PendingStatus
import com.example.pendingupdates.infrastructure.CachingTemplateCatalog
import com.example.pendingupdates.infrastructure.InMemoryStateRepository
import com.example.pendingupdates.infrastructure.InMemoryTemplateCatalog
import com.example.pendingupdates.ports.ChangeType
import com.example.pendingupdates.ports.DiffEntry
import com.example.pendingupdates.ports.ReleaseNotes
import com.example.pendingupdates.ports.TemplateDiffTool
import com.example.pendingupdates.state
import com.example.pendingupdates.v
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReadPathTest {
    private val clock = MutableClock()
    private val states = InMemoryStateRepository()
    private val store = InMemoryTemplateCatalog()
    private val cached = CachingTemplateCatalog(store, Duration.ofMinutes(1), clock)
    private val list = EngagementListService(states, cached)

    @Test
    fun `list shows pending without loading any engagement`() {
        states.save(FIRM, state(4))
        store.publish(TPL, v(5))

        val item = list.list(FIRM).single()

        assertIs<PendingStatus.Pending>(item.status)
    }

    @Test
    fun `a publish is picked up once the cache expires`() {
        states.save(FIRM, state(5))
        store.publish(TPL, v(5))
        assertEquals(PendingStatus.UpToDate, list.list(FIRM).single().status)

        store.publish(TPL, v(6))
        assertEquals(PendingStatus.UpToDate, list.list(FIRM).single().status) // still cached
        assertEquals(1, store.lookups)

        clock.advance(Duration.ofMinutes(1))
        assertIs<PendingStatus.Pending>(list.list(FIRM).single().status)
        assertEquals(2, store.lookups)
    }

    @Test
    fun `change list is computed once per range and shared`() {
        var diffCalls = 0
        val diffTool = TemplateDiffTool { _, _, _ ->
            diffCalls++
            listOf(DiffEntry("plan/procedures/revenue-cutoff", "Revenue cut-off testing procedure", ChangeType.MODIFIED))
        }
        val notes = ReleaseNotes { _, version -> "Notes for $version" }
        val summaries = ChangeSummaryService(diffTool, notes)
        val pending = PendingStatus.Pending(
            v(4), v(6),
            listOf(PendingStatus.VersionEntry(v(5), true), PendingStatus.VersionEntry(v(6), false)),
        )

        val first = summaries.summarize(TPL, pending)
        val second = summaries.summarize(TPL, pending)

        assertEquals(1, diffCalls)
        assertEquals(listOf("Modified: Revenue cut-off testing procedure"), first.changeList)
        assertEquals(first.hash, second.hash)
        assertEquals(listOf(true, false), first.versionNotes.map { it.previouslyDeclined })
    }
}
