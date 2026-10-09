package com.example.pendingupdates.application

import com.example.pendingupdates.domain.EngagementId
import com.example.pendingupdates.domain.EngagementTemplateState
import com.example.pendingupdates.domain.FirmId
import com.example.pendingupdates.domain.TemplateVersion
import com.example.pendingupdates.ports.EngagementStore
import com.example.pendingupdates.ports.EngagementTemplateStateRepository
import com.example.pendingupdates.ports.Metrics
import com.example.pendingupdates.ports.TemplateCatalog
import java.time.Clock

/**
 * One-time migration for engagements that existed before this feature was deployed.
 * The only place the slow (~1 minute) load is used in bulk.
 *
 * - Resumable: engagements that already have a row are skipped.
 * - Safe alongside live traffic: it only inserts missing rows, so it never overwrites a row the
 *   engagement system wrote in the meantime.
 * - Concurrency and scheduling (capped workers, off-peak) are left to the runner that calls [run].
 */
class BackfillJob(
    private val engagements: EngagementStore,
    private val states: EngagementTemplateStateRepository,
) {

    data class Result(val inserted: Int, val skipped: Int)

    fun run(firm: FirmId): Result {
        val existing = states.findAll(firm).map { it.engagementId }.toSet()
        var inserted = 0
        var skipped = 0
        for (id in engagements.engagementIds(firm)) {
            if (id in existing) { skipped++; continue }
            val ref = engagements.loadTemplateRef(firm, id) // slow load
            // Pre-existing engagements have no recorded decisions, so acknowledged = applied.
            val row = EngagementTemplateState.created(id, ref.templateId, ref.version)
            if (states.insertIfAbsent(firm, row)) inserted++ else skipped++
        }
        return Result(inserted, skipped)
    }
}

/**
 * Reconciliation, built into normal use: when a user opens an engagement it is already loaded,
 * so the engagement system passes the real version here to compare with the tracking row.
 */
class Reconciler(
    private val states: EngagementTemplateStateRepository,
    private val metrics: Metrics,
    private val clock: Clock,
) {

    enum class Outcome { MATCH, REPAIRED, CONCURRENT_CHANGE, MISSING_ROW }

    fun verifyOnOpen(firm: FirmId, engagementId: EngagementId, actualVersion: TemplateVersion): Outcome {
        val row = states.find(firm, engagementId) ?: return Outcome.MISSING_ROW
        if (row.appliedVersion == actualVersion) {
            states.compareAndSet(firm, row, row.copy(lastVerifiedAt = clock.instant()))
            return Outcome.MATCH
        }

        // Drift should never happen; it means a bug in the write path. Alert, then repair.
        metrics.projectionDrift(firm, engagementId)
        val repaired = row.copy(
            appliedVersion = actualVersion,
            acknowledgedVersion = maxOf(row.acknowledgedVersion, actualVersion),
            lastVerifiedAt = clock.instant(),
        )
        return if (states.compareAndSet(firm, row, repaired)) Outcome.REPAIRED else Outcome.CONCURRENT_CHANGE
    }
}

/**
 * Runs every few minutes per firm. It answers two observability questions without waiting for a
 * user: how many engagements each new version affects, and whether the check is running for
 * every firm (each run is a heartbeat). It's a database query, never an engagement load.
 */
class MetricsJob(
    private val states: EngagementTemplateStateRepository,
    private val catalog: TemplateCatalog,
    private val metrics: Metrics,
) {

    fun run(firm: FirmId) {
        // Skeleton: computed in memory. In production this is one GROUP BY query (see schema.sql).
        val rows = states.findAll(firm).filterNot { it.archived }
        val latest = catalog.latestVersions(rows.map { it.templateId }.toSet())
        rows.groupBy { it.templateId }.forEach { (templateId, group) ->
            val newest = latest[templateId] ?: return@forEach
            metrics.affectedEngagements(firm, templateId, newest, group.count { it.acknowledgedVersion < newest })
        }
        metrics.heartbeat(firm)
    }
}
