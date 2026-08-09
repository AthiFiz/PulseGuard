CREATE TABLE monitors (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    project_id           BIGINT        NOT NULL,
    name                 VARCHAR(150)  NOT NULL,
    description          VARCHAR(500)  NULL,
    url                  VARCHAR(2048) NOT NULL,
    http_method          VARCHAR(16)   NOT NULL DEFAULT 'GET',
    expected_status_code INT           NOT NULL,
    interval_seconds     INT           NOT NULL,
    timeout_seconds      INT           NOT NULL,
    failure_threshold    INT           NOT NULL,
    consecutive_failures INT           NOT NULL DEFAULT 0,
    current_status       VARCHAR(32)   NOT NULL DEFAULT 'UNKNOWN',
    last_checked_at      DATETIME(6)   NULL,
    next_check_at        DATETIME(6)   NULL,
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,
    CONSTRAINT pk_monitors PRIMARY KEY (id),
    -- Monitors belong to their project; deleting the project removes them.
    CONSTRAINT fk_monitors_project FOREIGN KEY (project_id)
        REFERENCES projects (id)
        ON DELETE CASCADE
        ON UPDATE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- Listing the monitors of a project.
CREATE INDEX idx_monitors_project ON monitors (project_id);

-- Supports the worker's future "find monitors that are due" query, which will
-- filter on status and next_check_at together.
CREATE INDEX idx_monitors_status_next_check ON monitors (current_status, next_check_at);
