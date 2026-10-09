package com.example.pendingupdates.domain

/** What the engagement list shows for one engagement. */
sealed interface PendingStatus {
    data object UpToDate : PendingStatus
    data object Archived : PendingStatus

    /** The template isn't known to the template store (or the lookup failed). Never shown as up to date. */
    data object Unknown : PendingStatus

    /**
     * @property diffFrom the version the engagement actually holds. The summary is one cumulative
     *   diff from here to [diffTo], because declined versions were never applied.
     * @property versions every version in (diffFrom, diffTo], for the per-version changelog.
     */
    data class Pending(
        val diffFrom: TemplateVersion,
        val diffTo: TemplateVersion,
        val versions: List<VersionEntry>,
    ) : PendingStatus

    data class VersionEntry(val version: TemplateVersion, val previouslyDeclined: Boolean)
}

object PendingRules {

    fun status(state: EngagementTemplateState, latest: TemplateVersion?): PendingStatus {
        if (state.archived) return PendingStatus.Archived
        if (latest == null) return PendingStatus.Unknown
        if (latest <= state.acknowledgedVersion) return PendingStatus.UpToDate

        val versions = ((state.appliedVersion.value + 1)..latest.value).map { v ->
            PendingStatus.VersionEntry(TemplateVersion(v), previouslyDeclined = v <= state.acknowledgedVersion.value)
        }
        return PendingStatus.Pending(diffFrom = state.appliedVersion, diffTo = latest, versions = versions)
    }
}
