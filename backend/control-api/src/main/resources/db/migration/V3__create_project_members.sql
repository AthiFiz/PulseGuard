CREATE TABLE project_members (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    project_id BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    role       VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_project_members PRIMARY KEY (id),
    -- A membership has no meaning without its project, so removing a project
    -- removes its memberships.
    CONSTRAINT fk_project_members_project FOREIGN KEY (project_id)
        REFERENCES projects (id)
        ON DELETE CASCADE
        ON UPDATE RESTRICT,
    -- Deleting a user is deliberately blocked while memberships remain, so
    -- membership rows are never silently orphaned.
    CONSTRAINT fk_project_members_user FOREIGN KEY (user_id)
        REFERENCES users (id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,
    CONSTRAINT uq_project_members_project_user UNIQUE (project_id, user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- The unique constraint already indexes (project_id, user_id); this supports
-- the reverse lookup of "which projects does this user belong to".
CREATE INDEX idx_project_members_user ON project_members (user_id);
