package com.example.pendingupdates.domain

import com.example.pendingupdates.state
import com.example.pendingupdates.v
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PendingRulesTest {

    @Test
    fun `engagement on the latest version is up to date`() {
        assertEquals(PendingStatus.UpToDate, PendingRules.status(state(4), latest = v(4)))
    }

    @Test
    fun `accumulated versions become one cumulative summary`() {
        val status = PendingRules.status(state(4), latest = v(7))

        assertIs<PendingStatus.Pending>(status)
        assertEquals(v(4) to v(7), status.diffFrom to status.diffTo)
        assertEquals(listOf(v(5), v(6), v(7)), status.versions.map { it.version })
    }

    @Test
    fun `declining clears pending until a newer version is published`() {
        assertEquals(PendingStatus.UpToDate, PendingRules.status(state(4).declined(v(5)), latest = v(5)))
    }

    @Test
    fun `after a decline the next summary still starts from the applied version`() {
        val status = PendingRules.status(state(4).declined(v(5)), latest = v(6))

        assertIs<PendingStatus.Pending>(status)
        assertEquals(v(4), status.diffFrom)
        assertEquals(
            listOf(PendingStatus.VersionEntry(v(5), previouslyDeclined = true), PendingStatus.VersionEntry(v(6), previouslyDeclined = false)),
            status.versions,
        )
    }

    @Test
    fun `unknown template is never shown as up to date`() {
        assertEquals(PendingStatus.Unknown, PendingRules.status(state(4), latest = null))
    }

    @Test
    fun `archived engagements are never pending`() {
        assertEquals(PendingStatus.Archived, PendingRules.status(state(4, archived = true), latest = v(9)))
    }
}
