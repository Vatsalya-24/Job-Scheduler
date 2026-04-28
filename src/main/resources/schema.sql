-- ============================================================
-- Orbit Platform — Full Schema
-- PostgreSQL 16+  |  run with spring.sql.init.mode=always
-- ============================================================

-- ── Core job tables ──────────────────────────────────────────

CREATE TABLE IF NOT EXISTS jobs (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name             VARCHAR(255)  NOT NULL,
    cron_expression  VARCHAR(255)  NOT NULL,
    target           VARCHAR(255)  NOT NULL,
    status           VARCHAR(20)   NOT NULL
                         CHECK (status IN ('ACTIVE','PAUSED','DISABLED')),
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_fire_time   TIMESTAMP,
    version          BIGINT        NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS job_executions (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id        BIGINT       NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    status        VARCHAR(20)  NOT NULL
                      CHECK (status IN ('STARTED','SUCCESS','FAILED','TIMEOUT')),
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    error_message TEXT
);

CREATE TABLE IF NOT EXISTS retry_log (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    execution_id   BIGINT  NOT NULL REFERENCES job_executions(id) ON DELETE CASCADE,
    attempt_number INT     NOT NULL,
    fired_at       TIMESTAMP
);

-- ── Webhook tables ────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS webhooks (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id        BIGINT        NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    url           VARCHAR(2048) NOT NULL,
    secret_key    VARCHAR(255),
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    event_filter  VARCHAR(20)   NOT NULL DEFAULT 'ALL'
                      CHECK (event_filter IN ('ALL','STARTED','SUCCESS','FAILED')),
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS webhook_deliveries (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    webhook_id       BIGINT      NOT NULL REFERENCES webhooks(id) ON DELETE CASCADE,
    job_execution_id BIGINT,
    event_type       VARCHAR(20),
    payload          TEXT,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING','DELIVERED','FAILED','EXHAUSTED')),
    http_status_code INT,
    response_body    TEXT,
    attempt_count    INT         NOT NULL DEFAULT 0,
    max_attempts     INT         NOT NULL DEFAULT 5,
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at     TIMESTAMP,
    next_retry_at    TIMESTAMP,
    error_message    TEXT
);

-- ── Indexes ───────────────────────────────────────────────────

-- Scheduler hot path
CREATE INDEX IF NOT EXISTS idx_jobs_status_fire_time  ON jobs (status, next_fire_time);
CREATE INDEX IF NOT EXISTS idx_jobs_next_fire_time     ON jobs (next_fire_time);

-- Execution queries
CREATE INDEX IF NOT EXISTS idx_job_executions_job_id   ON job_executions (job_id);
CREATE INDEX IF NOT EXISTS idx_job_executions_status   ON job_executions (status);
CREATE INDEX IF NOT EXISTS idx_job_executions_started  ON job_executions (started_at DESC);

-- Retry log
CREATE INDEX IF NOT EXISTS idx_retry_log_execution_id  ON retry_log (execution_id);

-- Webhook lookup
CREATE INDEX IF NOT EXISTS idx_webhooks_job_id         ON webhooks (job_id);
CREATE INDEX IF NOT EXISTS idx_webhooks_active         ON webhooks (active, job_id);
CREATE INDEX IF NOT EXISTS idx_webhook_del_webhook_id  ON webhook_deliveries (webhook_id);
CREATE INDEX IF NOT EXISTS idx_webhook_del_status      ON webhook_deliveries (status);

-- Partial index for retry poller — only indexes rows that need processing
CREATE INDEX IF NOT EXISTS idx_webhook_del_retry
    ON webhook_deliveries (status, next_retry_at)
    WHERE status IN ('PENDING','FAILED');
