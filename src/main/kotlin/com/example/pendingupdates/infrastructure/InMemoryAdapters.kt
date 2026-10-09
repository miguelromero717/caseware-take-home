package com.example.pendingupdates.infrastructure

import com.example.pendingupdates.domain.DecisionLogEntry
import com.example.pendingupdates.domain.EngagementId
import com.example.pendingupdates.domain.EngagementTemplateState
import com.example.pendingupdates.domain.FirmId
import com.example.pendingupdates.domain.TemplateId
import com.example.pendingupdates.domain.TemplateVersion
import com.example.pendingupdates.ports.DecisionLog
import com.example.pendingupdates.ports.EngagementStore
import com.example.pendingupdates.ports.EngagementTemplateStateRepository
import com.example.pendingupdates.ports.FirmTransactions
import com.example.pendingupdates.ports.Metrics
import com.example.pendingupdates.ports.TemplateCatalog
import com.example.pendingupdates.ports.TemplateRef

/*
 * In-memory adapters for tests and local runs. Production adapters (JDBC against the firm's
 * database, an HTTP client for the template store, CloudWatch for metrics) implement the same
 * ports. See src/main/resources/db/schema.sql for the tables.
 */

/** Anything that can take part in an in-memory transaction. */
interface Snapshotting {
    fun snapshot(): Any
    fun restore(snapshot: Any)
}

/** Simulates one DB transaction: on any exception, every participant is rolled back. */
class InMemoryTransactions(private vararg val participants: Snapshotting) : FirmTransactions {
    override fun <T> inTransaction(firm: FirmId, block: () -> T): T {
        val snapshots = participants.map { it to it.snapshot() }
        return try {
            block()
        } catch (e: Exception) {
            snapshots.forEach { (participant, snapshot) -> participant.restore(snapshot) }
            throw e
        }
    }
}

class InMemoryStateRepository : EngagementTemplateStateRepository, Snapshotting {
    private val rows = mutableMapOf<Pair<FirmId, EngagementId>, EngagementTemplateState>()

    override fun find(firm: FirmId, engagementId: EngagementId) = rows[firm to engagementId]
    override fun findAll(firm: FirmId) = rows.filterKeys { it.first == firm }.values.toList()
    override fun save(firm: FirmId, state: EngagementTemplateState) { rows[firm to state.engagementId] = state }

    override fun insertIfAbsent(firm: FirmId, state: EngagementTemplateState): Boolean =
        rows.putIfAbsent(firm to state.engagementId, state) == null

    override fun compareAndSet(firm: FirmId, expected: EngagementTemplateState, updated: EngagementTemplateState): Boolean {
        val key = firm to expected.engagementId
        if (rows[key] != expected) return false
        rows[key] = updated
        return true
    }

    override fun snapshot(): Any = rows.toMap()

    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) {
        rows.clear()
        rows.putAll(snapshot as Map<Pair<FirmId, EngagementId>, EngagementTemplateState>)
    }
}

class InMemoryDecisionLog : DecisionLog, Snapshotting {
    private val entries = mutableListOf<Pair<FirmId, DecisionLogEntry>>()

    override fun append(firm: FirmId, entry: DecisionLogEntry) { entries += firm to entry }
    override fun history(firm: FirmId, engagementId: EngagementId?) =
        entries.filter { it.first == firm && (engagementId == null || it.second.engagementId == engagementId) }.map { it.second }

    override fun snapshot(): Any = entries.toList()

    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) {
        entries.clear()
        entries.addAll(snapshot as List<Pair<FirmId, DecisionLogEntry>>)
    }
}

class InMemoryTemplateCatalog : TemplateCatalog {
    private val latest = mutableMapOf<TemplateId, TemplateVersion>()
    var lookups = 0
        private set

    fun publish(templateId: TemplateId, version: TemplateVersion) { latest[templateId] = version }

    override fun latestVersions(templateIds: Set<TemplateId>): Map<TemplateId, TemplateVersion> {
        lookups++
        return latest.filterKeys { it in templateIds }
    }
}

class FakeEngagementStore : EngagementStore {
    val templateRefs = mutableMapOf<Pair<FirmId, EngagementId>, TemplateRef>()
    var slowLoads = 0
        private set
    var failNextApply = false

    override fun engagementIds(firm: FirmId) = templateRefs.keys.filter { it.first == firm }.map { it.second }

    override fun loadTemplateRef(firm: FirmId, engagementId: EngagementId): TemplateRef {
        slowLoads++
        return templateRefs.getValue(firm to engagementId)
    }

    override fun applyTemplateVersion(firm: FirmId, engagementId: EngagementId, version: TemplateVersion) {
        if (failNextApply) { failNextApply = false; error("Simulated failure applying $version") }
        val key = firm to engagementId
        templateRefs[key] = templateRefs.getValue(key).copy(version = version)
    }
}

class RecordingMetrics : Metrics {
    val affected = mutableListOf<Triple<FirmId, TemplateId, Int>>()
    val heartbeats = mutableListOf<FirmId>()
    val drifts = mutableListOf<EngagementId>()

    override fun affectedEngagements(firm: FirmId, templateId: TemplateId, latest: TemplateVersion, count: Int) {
        affected += Triple(firm, templateId, count)
    }
    override fun heartbeat(firm: FirmId) { heartbeats += firm }
    override fun projectionDrift(firm: FirmId, engagementId: EngagementId) { drifts += engagementId }
}
