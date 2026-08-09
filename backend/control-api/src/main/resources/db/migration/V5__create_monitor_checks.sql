CREATE TABLE monitor_checks (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    monitor_id       BIGINT        NOT NULL,
    checked_at       DATETIME(6)   NOT NULL,
    outcome          VARCHAR(16)   NOT NULL,
    -- Nullable: a timeout or connection failure produces no HTTP response.
    http_status_code INT           NULL,
    response_time_ms INT           NULL,
    error_type       VARCHAR(32)   NULL,
    error_message    VARCHAR(1000) NULL,
    CONSTRAINT pk_monitor_checks PRIMARY KEY (id),
    -- Check history belongs to its monitor; deleting the monitor discards it.
    CONSTRAINT fk_monitor_checks_monitor FOREIGN KEY (monitor_id)
        REFERENCES monitors (id)
        ON DELETE CASCADE
        ON UPDATE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- Monitor history is read newest-first for one monitor, so the index leads
-- with monitor_id and orders by checked_at.
CREATE INDEX idx_monitor_checks_monitor_checked_at ON monitor_checks (monitor_id, checked_at);
