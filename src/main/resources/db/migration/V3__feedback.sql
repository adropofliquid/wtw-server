-- In-app feedback (contract: whattowear/db/IN_APP_FEEDBACK.md). Append-only; resends de-duplicate on id.
CREATE TABLE feedback (
    id           uuid         PRIMARY KEY,
    user_id      varchar(128) NOT NULL,
    body_uid     varchar(128) NOT NULL,
    message      text         NOT NULL,
    category     varchar(16),
    can_reply    boolean      NOT NULL DEFAULT false,
    email        varchar(320),
    app_version  varchar(64)  NOT NULL,
    platform     varchar(16)  NOT NULL,
    os_version   varchar(64)  NOT NULL,
    created_at   timestamptz  NOT NULL,
    received_at  timestamptz  NOT NULL DEFAULT now(),
    status       varchar(16)  NOT NULL DEFAULT 'new'
);
CREATE INDEX idx_feedback_received_at ON feedback (received_at DESC);
CREATE INDEX idx_feedback_user ON feedback (user_id);
