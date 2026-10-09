# Pending template updates (v2): implementation skeleton

A small Kotlin skeleton of the v2 design. The decision rules and application services are real code. Infrastructure is represented by ports, with in-memory adapters for tests. Production adapters (JDBC against the firm's database, an HTTP client for the template store, CloudWatch for metrics) would implement the same interfaces.

## How the code maps to the design

| Design component | Code |
|---|---|
| Tracking table `engagement_template_state` | `domain/Model.kt` (`EngagementTemplateState`), `db/schema.sql` |
| Two-version rules: pending, cumulative summary range | `domain/PendingRules.kt` |
| Stale-decision check | `domain/DecisionRules.kt` |
| Engagement system writes the row in its own transaction | `application/EngagementLifecycleHooks.kt` (create, archive), `application/DecisionService.kt` (apply, decline) |
| Decision log | `ports/Ports.kt` (`DecisionLog`), `db/schema.sql` |
| Engagement list API | `application/EngagementListService.kt` |
| Latest versions, cached | `infrastructure/CachingTemplateCatalog.kt` |
| Change summaries, computed once per range | `application/ChangeSummaryService.kt` |
| Backfill (one-time migration) | `application/BackgroundJobs.kt` (`BackfillJob`) |
| Reconciliation ("verify on open") | `application/BackgroundJobs.kt` (`Reconciler`) |
| Metrics job (counts and heartbeat) | `application/BackgroundJobs.kt` (`MetricsJob`) |

## Package layout

```
domain/          pure rules, no I/O
ports/           interfaces to the firm database, engagement system, template store, metrics
application/     use cases wiring rules and ports together
infrastructure/  in-memory adapters and the latest-version cache
resources/db/    schema for the two tables added to each firm's database
```

## Decisions worth noting

- **One transaction.** `DecisionService` changes the engagement, updates the row and appends to the decision log inside `FirmTransactions.inTransaction`. A failure rolls all three back (see `DecisionServiceTest`).
- **Fresh read for decisions, cached read for the list.** The list uses `CachingTemplateCatalog`; `DecisionService` takes the uncached catalog, so the stale check sees the real latest version.
- **Backfill only inserts.** `insertIfAbsent` means it can run alongside live traffic without overwriting rows the engagement system wrote.
- **Reconciliation repairs with compare-and-set,** so it can't clobber a concurrent apply or decline.
- **Counts only leave the firm.** `Metrics` takes counts, never engagement IDs or content.

## Running the tests

```
gradle test
```

Requires JDK 21. `EndToEndTest` replays the "engagement 17" walkthrough from the design doc (create, publish, decline, publish again, apply) and asserts that no engagement is ever loaded to answer what's pending.
