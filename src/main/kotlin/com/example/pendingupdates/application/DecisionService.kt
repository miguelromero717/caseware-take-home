package com.example.pendingupdates.application

import com.example.pendingupdates.domain.DecisionCommand
import com.example.pendingupdates.domain.DecisionKind
import com.example.pendingupdates.domain.DecisionLogEntry
import com.example.pendingupdates.domain.DecisionResult
import com.example.pendingupdates.domain.DecisionRules
import com.example.pendingupdates.ports.DecisionLog
import com.example.pendingupdates.ports.EngagementStore
import com.example.pendingupdates.ports.EngagementTemplateStateRepository
import com.example.pendingupdates.ports.FirmTransactions
import com.example.pendingupdates.ports.TemplateCatalog
import java.time.Clock

/**
 * Apply / decline. One transaction covers the engagement change, the tracking row and the
 * decision log entry: they commit together or not at all.
 *
 * [freshCatalog] must NOT be the cached catalog: the stale check needs the real latest version.
 */
class DecisionService(
    private val transactions: FirmTransactions,
    private val states: EngagementTemplateStateRepository,
    private val decisionLog: DecisionLog,
    private val engagements: EngagementStore,
    private val freshCatalog: TemplateCatalog,
    private val clock: Clock,
) {

    fun decide(command: DecisionCommand): DecisionResult = transactions.inTransaction(command.firmId) {
        val state = states.find(command.firmId, command.engagementId)
            ?: return@inTransaction DecisionResult.Rejected("Unknown engagement ${command.engagementId.value}")
        val latest = freshCatalog.latestVersions(setOf(state.templateId))[state.templateId]

        val result = DecisionRules.evaluate(command, state, latest)
        if (result is DecisionResult.Accepted) {
            if (command.kind == DecisionKind.APPLY) {
                engagements.applyTemplateVersion(command.firmId, command.engagementId, command.targetVersion)
            }
            states.save(command.firmId, result.newState)
            decisionLog.append(
                command.firmId,
                DecisionLogEntry(
                    engagementId = command.engagementId,
                    templateId = state.templateId,
                    kind = command.kind,
                    fromVersion = result.logEntryFrom,
                    toVersion = command.targetVersion,
                    decidedBy = command.userId,
                    decidedAt = clock.instant(),
                    summaryHash = command.summaryHash,
                ),
            )
        }
        result
    }
}
