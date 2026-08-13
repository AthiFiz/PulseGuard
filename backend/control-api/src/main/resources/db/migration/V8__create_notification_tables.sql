-- The Notification Service's two tables.
--
-- Owned by the Control API because it owns the schema, but never read or
-- written by it. The Notification Service maps them under ddl-auto=validate.

-- The consumer's inbox: one row per incident event that has been processed.
--
-- Kafka delivery is at-least-once, so the same event can arrive more than once.
-- This table is what makes processing idempotent — the unique event_id below is
-- the durable guard, surviving restarts in a way an in-memory set would not.
CREATE TABLE consumed_events (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    event_id        CHAR(36)     NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    schema_version  INT          NOT NULL,
    -- The business timestamp from the event, not the time it was consumed.
    occurred_at     DATETIME(6)  NOT NULL,
    -- Historical values, deliberately without foreign keys. An event records
    -- something that already happened; deleting the project or monitor later
    -- must not erase the record that it was notified.
    incident_id     BIGINT       NOT NULL,
    project_id      BIGINT       NOT NULL,
    monitor_id      BIGINT       NOT NULL,
    -- Traceability back to the exact Kafka record. Not an identity: the same
    -- event redelivered arrives at a different offset.
    kafka_topic     VARCHAR(255) NOT NULL,
    kafka_partition INT          NOT NULL,
    kafka_offset    BIGINT       NOT NULL,
    -- Kept verbatim for auditing, so a question about an old notification is
    -- answered by what the event said rather than by today's database.
    raw_payload     LONGTEXT     NOT NULL,
    consumed_at     DATETIME(6)  NOT NULL,
    CONSTRAINT pk_consumed_events PRIMARY KEY (id),
    CONSTRAINT uq_consumed_events_event_id UNIQUE (event_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- One row per recipient per event: the intention to send an email, and what
-- became of it.
CREATE TABLE notification_deliveries (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    event_id        CHAR(36)      NOT NULL,
    incident_id     BIGINT        NOT NULL,
    project_id      BIGINT        NOT NULL,
    monitor_id      BIGINT        NOT NULL,
    -- A snapshot. If the user later changes their address, an email already
    -- queued still goes where it was addressed when the incident happened.
    recipient_email VARCHAR(255)  NOT NULL,
    channel         VARCHAR(32)   NOT NULL,
    status          VARCHAR(32)   NOT NULL,
    -- Composed once, when the event was consumed, and stored. The message
    -- describes the incident as it was, not as the database is now.
    subject         VARCHAR(500)  NOT NULL,
    body            LONGTEXT      NOT NULL,
    attempt_count   INT           NOT NULL DEFAULT 0,
    -- Set only while PENDING; null once the delivery is finished either way.
    next_attempt_at DATETIME(6)   NULL,
    last_attempt_at DATETIME(6)   NULL,
    sent_at         DATETIME(6)   NULL,
    -- Bounded and human-readable. Never a stack trace, never credentials.
    last_error      VARCHAR(1000) NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    CONSTRAINT pk_notification_deliveries PRIMARY KEY (id),
    -- The second deduplication guard. Even if an event were somehow processed
    -- twice, one recipient cannot be queued the same email twice.
    CONSTRAINT uq_notification_deliveries_recipient
        UNIQUE (event_id, recipient_email, channel),
    -- A delivery belongs to the event that caused it.
    CONSTRAINT fk_notification_deliveries_event FOREIGN KEY (event_id)
        REFERENCES consumed_events (event_id)
        ON DELETE CASCADE
        ON UPDATE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- The delivery scheduler's only query: what is pending and due, oldest first.
CREATE INDEX idx_notification_deliveries_due
    ON notification_deliveries (status, next_attempt_at, id);
