package com.example.pendingupdates.application

import com.example.pendingupdates.domain.EngagementId
import com.example.pendingupdates.domain.FirmId
import com.example.pendingupdates.domain.PendingRules
import com.example.pendingupdates.domain.PendingStatus
import com.example.pendingupdates.domain.TemplateId
import com.example.pendingupdates.ports.EngagementTemplateStateRepository
import com.example.pendingupdates.ports.TemplateCatalog

data class EngagementListItem(
    val engagementId: EngagementId,
    val templateId: TemplateId,
    val status: PendingStatus,
)

/**
 * Backs the engagement list screen. One read of the firm's tracking table plus one cached
 * lookup of latest versions. It never loads an engagement.
 */
class EngagementListService(
    private val states: EngagementTemplateStateRepository,
    private val cachedCatalog: TemplateCatalog,
) {

    fun list(firm: FirmId): List<EngagementListItem> {
        val rows = states.findAll(firm)
        val latest = cachedCatalog.latestVersions(rows.map { it.templateId }.toSet())
        return rows.map { row ->
            EngagementListItem(row.engagementId, row.templateId, PendingRules.status(row, latest[row.templateId]))
        }
    }
}
