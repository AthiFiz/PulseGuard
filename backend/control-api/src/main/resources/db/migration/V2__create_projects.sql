CREATE TABLE projects (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(150) NOT NULL,
    description VARCHAR(500) NULL,
    created_by  BIGINT       NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_projects PRIMARY KEY (id),
    -- RESTRICT: a user who still owns projects must not be deletable, so
    -- projects can never be left pointing at a missing creator.
    CONSTRAINT fk_projects_created_by FOREIGN KEY (created_by)
        REFERENCES users (id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_projects_created_by ON projects (created_by);
