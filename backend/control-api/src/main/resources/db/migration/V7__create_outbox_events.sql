-- The transactional outbox.
--
-- Rows are written by the Monitor Worker in the same transaction as the
-- incident they describe, and published to Kafka afterwards by a separate
-- scheduled job. That is what keeps monitoring working while Kafka is down:
-- the event is already durably recorded, and delivery catches up later.
--
-- The Control API owns this migration because it owns the schema, but it never
-- reads or writes the table.
CREATE TABLE outbox_events (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    -- The logical event identity, stable across republication. A consumer that
    -- sees the same event twice can recognise it by this value.
    event_id        CHAR(36)      NOT NULL,
    event_type      VARCHAR(64)   NOT NULL,
    -- What kind of thing the event is about, so future event families can share
    -- this table without another migration.
    aggregate_type  VARCHAR(64)   NOT NULL,
    aggregate_id    BIGINT        NOT NULL,
    -- Becomes the Kafka message key: the monitor id, so every event for one
    -- monitor lands on the same partition and keeps its order.
    partition_key   VARCHAR(128)  NOT NULL,
    -- The serialised event, published verbatim. Rebuilding it at publish time
    -- would describe the database as it is now rather than what happened.
    payload         LONGTEXT      NOT NULL,
    -- When the business event happened: the incident's own opened/resolved
    -- timestamp, never the time this row was written.
    occurred_at     DATETIME(6)   NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    -- Null while pending. Set only once a broker has acknowledged the send.
    published_at    DATETIME(6)   NULL,
    attempt_count   INT           NOT NULL DEFAULT 0,
    last_attempt_at DATETIME(6)   NULL,
    -- Bounded and human-readable. Never a stack trace.
    last_error      VARCHAR(1000) NULL,
    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT uq_outbox_events_event_id UNIQUE (event_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- There is deliberately no foreign key to incidents or monitors. An event
-- records something that already happened, and must stay publishable even if
-- the monitor it describes is deleted a moment later.

-- The publisher's only query: unpublished rows, oldest first.
CREATE INDEX idx_outbox_events_pending ON outbox_events (published_at, id);
