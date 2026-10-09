package com.example.pendingupdates.domain

/**
 * A user's apply/decline decision. [targetVersion] is the version the user was looking at:
 * it acts as an optimistic-concurrency token, so nobody is recorded as approving changes
 * they were never shown.
 */
data class DecisionCommand(
    val firmId: FirmId,
    val engagementId: EngagementId,
    val kind: DecisionKind,
    val targetVersion: TemplateVersion,
    val userId: UserId,
    val summaryHash: String,
)

sealed interface DecisionResult {
    data class Accepted(val newState: EngagementTemplateState, val logEntryFrom: TemplateVersion) : DecisionResult

    /** A newer version was published after the summary was rendered. The UI reloads and asks again. */
    data class Stale(val latest: TemplateVersion) : DecisionResult

    data class Rejected(val reason: String) : DecisionResult
}

object DecisionRules {

    /** [latest] must be a fresh read from the template store, never the cached one. */
    fun evaluate(command: DecisionCommand, state: EngagementTemplateState, latest: TemplateVersion?): DecisionResult {
        val status = PendingRules.status(state, latest)
        if (status !is PendingStatus.Pending) {
            return DecisionResult.Rejected("No pending update for ${state.engagementId.value} ($status)")
        }
        if (command.targetVersion > status.diffTo) {
            return DecisionResult.Rejected("${command.targetVersion} has not been published")
        }
        if (command.targetVersion < status.diffTo) {
            return DecisionResult.Stale(latest = status.diffTo)
        }

        val newState = when (command.kind) {
            DecisionKind.APPLY -> state.applied(command.targetVersion)
            DecisionKind.DECLINE -> state.declined(command.targetVersion)
        }
        return DecisionResult.Accepted(newState, logEntryFrom = state.appliedVersion)
    }
}
