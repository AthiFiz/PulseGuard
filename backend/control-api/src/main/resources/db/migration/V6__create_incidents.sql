CREATE TABLE incidents (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    monitor_id          BIGINT      NOT NULL,
    status              VARCHAR(16) NOT NULL,
    -- The time of the check that caused the outage, never the insert time, so
    -- an incident's duration is derived from observed events.
    opened_at           DATETIME(6) NOT NULL,
    -- Null while the incident is OPEN.
    resolved_at         DATETIME(6) NULL,
    -- Which checks caused the transitions. Nullable so the foreign keys below
    -- can null them out rather than block a delete; the worker always sets
    -- opening_check_id when it opens an incident.
    opening_check_id    BIGINT      NULL,
    resolution_check_id BIGINT      NULL,
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    CONSTRAINT pk_incidents PRIMARY KEY (id),
    -- Incident history belongs to its monitor; deleting the monitor discards
    -- it, exactly as monitor_checks already behaves.
    CONSTRAINT fk_incidents_monitor FOREIGN KEY (monitor_id)
        REFERENCES monitors (id)
        ON DELETE CASCADE
        ON UPDATE RESTRICT,
    CONSTRAINT fk_incidents_opening_check FOREIGN KEY (opening_check_id)
        REFERENCES monitor_checks (id)
        ON DELETE SET NULL
        ON UPDATE RESTRICT,
    CONSTRAINT fk_incidents_resolution_check FOREIGN KEY (resolution_check_id)
        REFERENCES monitor_checks (id)
        ON DELETE SET NULL
        ON UPDATE RESTRICT,
    -- An OPEN incident has not ended, and a RESOLVED one has. Nothing may
    -- claim both. resolution_check_id is deliberately not constrained: the
    -- foreign key above may null it out long after the incident was resolved,
    -- and losing the reference must not invalidate the record.
    CONSTRAINT chk_incidents_status_resolution CHECK (
        (status = 'OPEN' AND resolved_at IS NULL)
        OR (status = 'RESOLVED' AND resolved_at IS NOT NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- The worker runs this on every failed check: "is there already an open
-- incident for this monitor?". It is the hottest incident query in the system.
CREATE INDEX idx_incidents_monitor_status ON incidents (monitor_id, status);

-- Listing a project's incidents joins monitors -> incidents and orders by
-- opened_at, so the index leads with the join column and carries the sort.
CREATE INDEX idx_incidents_monitor_opened_at ON incidents (monitor_id, opened_at);
