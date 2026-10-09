package com.example.pendingupdates.ports

import com.example.pendingupdates.domain.DecisionLogEntry
import com.example.pendingupdates.domain.EngagementId
import com.example.pendingupdates.domain.EngagementTemplateState
import com.example.pendingupdates.domain.FirmId
import com.example.pendingupdates.domain.TemplateId
import com.example.pendingupdates.domain.TemplateVersion

// ---------------------------------------------------------------------------------------------
// Firm side: everything here lives in (or talks to) the firm's own database.
// Production adapters resolve the firm's DB through a firm-scoped role; see README.
// ---------------------------------------------------------------------------------------------

/** The per-firm tracking table, `engagement_template_state`. */
interface EngagementTemplateStateRepository {
    fun find(firm: FirmId, engagementId: EngagementId): EngagementTemplateState?
    fun findAll(firm: FirmId): List<EngagementTemplateState>
    fun save(firm: FirmId, state: EngagementTemplateState)

    /** Backfill only inserts missing rows; it must never overwrite a row the engagement system wrote. */
    fun insertIfAbsent(firm: FirmId, state: EngagementTemplateState): Boolean

    /** Reconciliation repairs with a conditional write, so it can't clobber a concurrent apply/decline. */
    fun compareAndSet(firm: FirmId, expected: EngagementTemplateState, updated: EngagementTemplateState): Boolean
}

/** Append-only audit trail of decisions. */
interface DecisionLog {
    fun append(firm: FirmId, entry: DecisionLogEntry)
    fun history(firm: FirmId, engagementId: EngagementId? = null): List<DecisionLogEntry>
}

/**
 * Runs a block in one database transaction of the firm's database.
 * The engagement change, the tracking row and the decision log commit together or not at all.
 */
interface FirmTransactions {
    fun <T> inTransaction(firm: FirmId, block: () -> T): T
}

/** The parts of the existing engagement system this design touches. */
interface EngagementStore {
    fun engagementIds(firm: FirmId): List<EngagementId>

    /** The slow (~1 minute) load. Only ever called by background work: backfill. */
    fun loadTemplateRef(firm: FirmId, engagementId: EngagementId): TemplateRef

    /** Applies template content to the engagement. Runs inside the caller's transaction. */
    fun applyTemplateVersion(firm: FirmId, engagementId: EngagementId, version: TemplateVersion)
}

data class TemplateRef(val templateId: TemplateId, val version: TemplateVersion)

// ---------------------------------------------------------------------------------------------
// Shared side: template data only. Only template IDs are ever sent here, never engagement data.
// ---------------------------------------------------------------------------------------------

/** "What is the latest version of these templates?" Backed by the template store. */
interface TemplateCatalog {
    fun latestVersions(templateIds: Set<TemplateId>): Map<TemplateId, TemplateVersion>
}

/** The existing, fast JSON diff between two versions of the same template. */
fun interface TemplateDiffTool {
    fun diff(templateId: TemplateId, from: TemplateVersion, to: TemplateVersion): List<DiffEntry>
}

/** One changed node of a template diff, e.g. path = "auditPlan/procedures/revenue-cutoff". */
data class DiffEntry(val path: String, val label: String, val change: ChangeType)

enum class ChangeType { ADDED, REMOVED, MODIFIED }

/** Author-written (or LLM-drafted, author-approved) note per version. Optional. */
fun interface ReleaseNotes {
    fun noteFor(templateId: TemplateId, version: TemplateVersion): String?
}

// ---------------------------------------------------------------------------------------------
// Observability
// ---------------------------------------------------------------------------------------------

interface Metrics {
    /** Counts only, never engagement IDs or content. */
    fun affectedEngagements(firm: FirmId, templateId: TemplateId, latest: TemplateVersion, count: Int)
    fun heartbeat(firm: FirmId)
    fun projectionDrift(firm: FirmId, engagementId: EngagementId)
}
