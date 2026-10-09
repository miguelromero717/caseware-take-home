package com.example.pendingupdates.application

import com.example.pendingupdates.domain.EngagementId
import com.example.pendingupdates.domain.EngagementTemplateState
import com.example.pendingupdates.domain.FirmId
import com.example.pendingupdates.domain.TemplateId
import com.example.pendingupdates.domain.TemplateVersion
import com.example.pendingupdates.ports.EngagementTemplateStateRepository

/**
 * Called by the engagement system *inside its own transaction* when it creates or archives
 * an engagement. Apply and decline go through [DecisionService], which uses the same table.
 *
 * Because the write shares the engagement change's transaction, the tracking table can't
 * disagree with the engagement except through a bug, which [Reconciler] detects.
 */
class EngagementLifecycleHooks(
    private val states: EngagementTemplateStateRepository,
) {

    fun onCreated(firm: FirmId, engagementId: EngagementId, templateId: TemplateId, version: TemplateVersion) {
        states.save(firm, EngagementTemplateState.created(engagementId, templateId, version))
    }

    fun onArchived(firm: FirmId, engagementId: EngagementId) {
        val state = states.find(firm, engagementId) ?: return
        states.save(firm, state.archived())
    }
}
