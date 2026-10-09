package com.example.pendingupdates.infrastructure

import com.example.pendingupdates.domain.TemplateId
import com.example.pendingupdates.domain.TemplateVersion
import com.example.pendingupdates.ports.TemplateCatalog
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches "latest version" per template for a short TTL (about a minute). This is why a publish
 * that affects every firm costs nothing extra: firms pick it up on their next list load.
 *
 * Use this for the list and the metrics job. Decisions use the uncached delegate.
 */
class CachingTemplateCatalog(
    private val delegate: TemplateCatalog,
    private val ttl: Duration,
    private val clock: Clock,
) : TemplateCatalog {

    private data class Entry(val version: TemplateVersion, val fetchedAt: Instant)

    private val cache = ConcurrentHashMap<TemplateId, Entry>()

    override fun latestVersions(templateIds: Set<TemplateId>): Map<TemplateId, TemplateVersion> {
        val now = clock.instant()
        val missing = templateIds.filter { id ->
            val entry = cache[id]
            entry == null || entry.fetchedAt.plus(ttl) <= now
        }.toSet()

        if (missing.isNotEmpty()) {
            delegate.latestVersions(missing).forEach { (id, version) -> cache[id] = Entry(version, now) }
        }
        return templateIds.mapNotNull { id -> cache[id]?.let { id to it.version } }.toMap()
    }
}
