-- Tables added to EACH firm's own database (PostgreSQL syntax).
-- Nothing here is created in the shared template database.

CREATE TABLE engagement_template_state (
    engagement_id         TEXT        PRIMARY KEY,
    template_id           TEXT        NOT NULL,
    applied_version       INTEGER     NOT NULL,   -- content the engagement actually holds; summaries start here
    acknowledged_version  INTEGER     NOT NULL,   -- newest version the user decided on; pending compares against this
    archived              BOOLEAN     NOT NULL DEFAULT FALSE,
    last_verified_at      TIMESTAMPTZ,            -- set by reconciliation
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT applied_le_acknowledged CHECK (applied_version <= acknowledged_version)
);

CREATE INDEX engagement_template_state_template ON engagement_template_state (template_id) WHERE NOT archived;

CREATE TABLE decision_log (
    id             BIGSERIAL   PRIMARY KEY,
    engagement_id  TEXT        NOT NULL,
    template_id    TEXT        NOT NULL,
    decision       TEXT        NOT NULL CHECK (decision IN ('APPLY', 'DECLINE')),
    from_version   INTEGER     NOT NULL,
    to_version     INTEGER     NOT NULL,
    decided_by     TEXT        NOT NULL,
    decided_at     TIMESTAMPTZ NOT NULL,
    summary_hash   TEXT        NOT NULL         -- proves which summary the user was shown
);

CREATE INDEX decision_log_engagement ON decision_log (engagement_id, decided_at);

-- Backfill: insert only, never overwrite a row the engagement system already wrote.
-- INSERT INTO engagement_template_state (engagement_id, template_id, applied_version, acknowledged_version)
-- VALUES (?, ?, ?, ?) ON CONFLICT (engagement_id) DO NOTHING;

-- Metrics job: affected engagements per template, given the latest versions from the template store.
-- SELECT s.template_id, count(*)
-- FROM engagement_template_state s
-- JOIN (VALUES (?, ?), ...) AS latest(template_id, version) ON latest.template_id = s.template_id
-- WHERE NOT s.archived AND s.acknowledged_version < latest.version
-- GROUP BY s.template_id;
