package com.example.pendingupdates.domain

import java.time.Instant

@JvmInline
value class FirmId(val value: String)

@JvmInline
value class EngagementId(val value: String)

@JvmInline
value class TemplateId(val value: String)

@JvmInline
value class UserId(val value: String)

/** Template versions are increasing integers per template (assumption #1). */
@JvmInline
value class TemplateVersion(val value: Int) : Comparable<TemplateVersion> {
    init {
        require(value >= 1) { "Template versions start at 1, got $value" }
    }

    override fun compareTo(other: TemplateVersion): Int = value.compareTo(other.value)
    override fun toString(): String = "v$value"
}

/**
 * One row of `engagement_template_state`, the per-firm tracking table.
 *
 * Two versions, because they answer two different questions:
 * - [appliedVersion]: the content the engagement actually holds. Summaries start here.
 * - [acknowledgedVersion]: the newest version the user has decided on (applied or declined).
 *   The pending check compares against this.
 *
 * Invariant: appliedVersion <= acknowledgedVersion.
 */
data class EngagementTemplateState(
    val engagementId: EngagementId,
    val templateId: TemplateId,
    val appliedVersion: TemplateVersion,
    val acknowledgedVersion: TemplateVersion,
    val archived: Boolean = false,
    val lastVerifiedAt: Instant? = null,
) {
    init {
        require(appliedVersion <= acknowledgedVersion) {
            "Invariant broken for ${engagementId.value}: applied $appliedVersion > acknowledged $acknowledgedVersion"
        }
    }

    fun applied(version: TemplateVersion): EngagementTemplateState =
        copy(appliedVersion = version, acknowledgedVersion = maxOf(acknowledgedVersion, version))

    /** Declining leaves the content untouched and only records the decision (assumption #3). */
    fun declined(version: TemplateVersion): EngagementTemplateState =
        copy(acknowledgedVersion = maxOf(acknowledgedVersion, version))

    fun archived(): EngagementTemplateState = copy(archived = true)

    companion object {
        fun created(engagementId: EngagementId, templateId: TemplateId, version: TemplateVersion) =
            EngagementTemplateState(engagementId, templateId, appliedVersion = version, acknowledgedVersion = version)
    }
}

enum class DecisionKind { APPLY, DECLINE }

/** Append-only audit record, written in the same transaction as the decision. */
data class DecisionLogEntry(
    val engagementId: EngagementId,
    val templateId: TemplateId,
    val kind: DecisionKind,
    val fromVersion: TemplateVersion,
    val toVersion: TemplateVersion,
    val decidedBy: UserId,
    val decidedAt: Instant,
    /** Hash of the summary the user was shown, so "what was I shown?" can always be answered. */
    val summaryHash: String,
)
