-- wtw-server schema, complete. Source: WTW_SERVER_BRIEF.md section 3, with the section 9 rulings:
--   9.1 no foreign keys anywhere (integrity lives in the service layer; link columns are indexed);
--   9.2 item attributes are one jsonb object holding the superset vocabulary.
-- All timestamps are timestamptz (UTC). 15 tables.

-- 3.1 Identity and profile ---------------------------------------------------
CREATE TABLE user_profile (
    id                varchar(128) PRIMARY KEY,
    display_name      text,
    style             varchar(32),
    vibes             text[]       NOT NULL DEFAULT '{}',
    events            text[]       NOT NULL DEFAULT '{}',
    timezone          varchar(64),
    last_notified_at  timestamptz,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_profile_timezone ON user_profile (timezone) WHERE timezone IS NOT NULL;

-- 3.2 Mirror (device is master) ---------------------------------------------
CREATE TABLE mirror_item (
    id              varchar(64)  PRIMARY KEY,
    user_id         varchar(128) NOT NULL,
    created_at      timestamptz  NOT NULL,
    chosen          varchar(16)  NOT NULL DEFAULT 'CUTOUT',
    reviewed        boolean      NOT NULL DEFAULT false,
    category        varchar(16),
    attributes      jsonb,
    price           numeric(12,2),
    currency        varchar(3),
    purchase_date   date,
    brand           text,
    archived        boolean      NOT NULL DEFAULT false,
    catalog_id      varchar(128),
    cutout_key      text,
    generated_key   text,
    source          varchar(16),
    pool_consent    boolean      NOT NULL DEFAULT false,
    pool_filter     varchar(16),
    updated_at      timestamptz  NOT NULL,
    deleted_at      timestamptz,
    synced_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_mirror_item_chosen CHECK (chosen IN ('ORIGINAL', 'CUTOUT', 'GENERATED'))
);
CREATE INDEX idx_mirror_item_user_sync ON mirror_item (user_id, synced_at);
CREATE INDEX idx_mirror_item_catalog ON mirror_item (catalog_id);

CREATE TABLE mirror_outfit (
    id              varchar(64)  PRIMARY KEY,
    user_id         varchar(128) NOT NULL,
    name            text         NOT NULL DEFAULT '',
    created_by_ai   boolean      NOT NULL DEFAULT false,
    occasion        text,
    note            text,
    layout          varchar(16),
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    deleted_at      timestamptz,
    synced_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_mirror_outfit_user_sync ON mirror_outfit (user_id, synced_at);

CREATE TABLE mirror_outfit_piece (
    outfit_id   varchar(64)  NOT NULL,
    item_id     varchar(64)  NOT NULL,
    user_id     varchar(128) NOT NULL,
    x           real         NOT NULL DEFAULT 0.5,
    y           real         NOT NULL DEFAULT 0.5,
    scale       real         NOT NULL DEFAULT 1,
    rotation    real         NOT NULL DEFAULT 0,
    z           integer      NOT NULL DEFAULT 0,
    updated_at  timestamptz  NOT NULL,
    PRIMARY KEY (outfit_id, item_id)
);
CREATE INDEX idx_mirror_outfit_piece_item ON mirror_outfit_piece (item_id);
CREATE INDEX idx_mirror_outfit_piece_user ON mirror_outfit_piece (user_id);

CREATE TABLE mirror_planned (
    user_id     varchar(128) NOT NULL,
    date        date         NOT NULL,
    outfit_id   varchar(64),
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    deleted_at  timestamptz,
    synced_at   timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, date),
    CONSTRAINT ck_mirror_planned_outfit CHECK (deleted_at IS NOT NULL OR outfit_id IS NOT NULL)
);
CREATE INDEX idx_mirror_planned_user_sync ON mirror_planned (user_id, synced_at);
CREATE INDEX idx_mirror_planned_outfit ON mirror_planned (outfit_id);

CREATE TABLE mirror_wear (
    user_id     varchar(128) NOT NULL,
    date        date         NOT NULL,
    status      varchar(10),
    outfit_id   varchar(64),
    item_ids    text[]       NOT NULL DEFAULT '{}',
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    deleted_at  timestamptz,
    synced_at   timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, date),
    CONSTRAINT ck_mirror_wear_status CHECK (status IN ('WORN', 'NOT_WORN'))
);
CREATE INDEX idx_mirror_wear_user_sync ON mirror_wear (user_id, synced_at);

CREATE TABLE mirror_packing_list (
    id          varchar(64)  PRIMARY KEY,
    user_id     varchar(128) NOT NULL,
    name        text         NOT NULL,
    start_date  date,
    end_date    date,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    deleted_at  timestamptz,
    synced_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_mirror_packing_list_user_sync ON mirror_packing_list (user_id, synced_at);

CREATE TABLE mirror_packing_item (
    list_id     varchar(64)  NOT NULL,
    item_id     varchar(64)  NOT NULL,
    user_id     varchar(128) NOT NULL,
    packed      boolean      NOT NULL DEFAULT false,
    updated_at  timestamptz  NOT NULL,
    PRIMARY KEY (list_id, item_id)
);
CREATE INDEX idx_mirror_packing_item_item ON mirror_packing_item (item_id);
CREATE INDEX idx_mirror_packing_item_user ON mirror_packing_item (user_id);

-- 3.3 Library -----------------------------------------------------------------
CREATE TABLE library_item (
    id           varchar(128) PRIMARY KEY,
    source       varchar(16)  NOT NULL,
    name         text         NOT NULL,
    file_name    text,
    category     varchar(16)  NOT NULL,
    description  text         NOT NULL,
    attributes   jsonb,
    brand        text,
    model        text,
    gender       varchar(16),
    keywords     text[]       NOT NULL DEFAULT '{}',
    vibes        text[]       NOT NULL DEFAULT '{}',
    styles       text[]       NOT NULL DEFAULT '{}',
    image_key    text         NOT NULL,
    active       boolean      NOT NULL DEFAULT true,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_library_item_source CHECK (source IN ('starter', 'generated'))
);
CREATE INDEX idx_library_item_source ON library_item (source);
CREATE INDEX idx_library_item_category ON library_item (category);

-- 3.4 AI ----------------------------------------------------------------------
CREATE TABLE ai_session (
    id                       varchar(64)  PRIMARY KEY,
    user_id                  varchar(128) NOT NULL,
    context                  varchar(16)  NOT NULL,
    target_date              date,
    day_type                 text,
    comfort                  text,
    vibes                    text[]       NOT NULL DEFAULT '{}',
    note                     text,
    style                    varchar(32),
    weather                  jsonb,
    wardrobe_snapshot        jsonb,
    recently_worn            jsonb,
    status                   varchar(16)  NOT NULL,
    initiator                varchar(32)  NOT NULL DEFAULT 'USER_INITIATED',
    attempt_number           integer      NOT NULL DEFAULT 0,
    too_warm                 boolean      NOT NULL DEFAULT false,
    too_cold                 boolean      NOT NULL DEFAULT false,
    too_casual               boolean      NOT NULL DEFAULT false,
    too_formal               boolean      NOT NULL DEFAULT false,
    different_top            boolean      NOT NULL DEFAULT false,
    different_bottom         boolean      NOT NULL DEFAULT false,
    rejected_suggestion_ids  text[]       NOT NULL DEFAULT '{}',
    accepted_suggestion_id   varchar(64),
    conversation_history     jsonb,
    started_at               timestamptz  NOT NULL,
    last_activity_at         timestamptz  NOT NULL,
    created_at               timestamptz  NOT NULL,
    updated_at               timestamptz  NOT NULL,
    CONSTRAINT ck_ai_session_context CHECK (context IN ('DAILY', 'PLANNER', 'WARDROBE')),
    CONSTRAINT ck_ai_session_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'ABANDONED'))
);
CREATE INDEX idx_ai_session_user_started ON ai_session (user_id, started_at);
CREATE INDEX idx_ai_session_status_activity ON ai_session (status, last_activity_at);
CREATE INDEX idx_ai_session_user_accepted ON ai_session (user_id, last_activity_at) WHERE accepted_suggestion_id IS NOT NULL;

CREATE TABLE ai_suggestion (
    id                varchar(64)  PRIMARY KEY,
    user_id           varchar(128) NOT NULL,
    session_id        varchar(64),
    kind              varchar(16)  NOT NULL,
    attempt_number    integer      NOT NULL DEFAULT 1,
    name              text,
    item_ids          text[]       NOT NULL DEFAULT '{}',
    reasoning         text,
    occasion          text,
    day_type          text,
    vibes             text[]       NOT NULL DEFAULT '{}',
    note              text,
    accepted          boolean      NOT NULL DEFAULT false,
    accepted_at       timestamptz,
    rejected          boolean      NOT NULL DEFAULT false,
    rejection_reason  varchar(32),
    feedback_text     text,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    CONSTRAINT ck_ai_suggestion_kind CHECK (kind IN ('session', 'legacy_batch'))
);
CREATE INDEX idx_ai_suggestion_user_updated ON ai_suggestion (user_id, updated_at);
CREATE INDEX idx_ai_suggestion_session ON ai_suggestion (session_id);
CREATE INDEX idx_ai_suggestion_user_created ON ai_suggestion (user_id, created_at);

-- 3.5 Ledger ------------------------------------------------------------------
CREATE TABLE ledger_entry (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     varchar(128) NOT NULL,
    bucket      varchar(16)  NOT NULL,
    delta       integer      NOT NULL,
    reason      varchar(32)  NOT NULL,
    week_start  date,
    ref         varchar(128),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_entry_bucket CHECK (bucket IN ('WEEKLY', 'PURCHASED'))
);
CREATE UNIQUE INDEX uq_ledger_ref ON ledger_entry (user_id, reason, ref) WHERE ref IS NOT NULL;
CREATE INDEX idx_ledger_entry_user_bucket_week ON ledger_entry (user_id, bucket, week_start);

CREATE TABLE revenuecat_event (
    id              varchar(128) PRIMARY KEY,
    type            varchar(48)  NOT NULL,
    app_user_id     varchar(128) NOT NULL,
    product_id      text,
    transaction_id  text,
    environment     varchar(16),
    payload         jsonb        NOT NULL,
    outcome         varchar(32),
    received_at     timestamptz  NOT NULL DEFAULT now(),
    processed_at    timestamptz,
    updated_at      timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_revenuecat_event_app_user ON revenuecat_event (app_user_id);

-- 3.6 Push --------------------------------------------------------------------
CREATE TABLE push_token (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     varchar(128) NOT NULL,
    fcm_token   text         NOT NULL,
    platform    varchar(10)  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_push_token_fcm_token UNIQUE (fcm_token)
);
CREATE INDEX idx_push_token_user ON push_token (user_id);

-- 3.7 Config ------------------------------------------------------------------
CREATE TABLE app_config (
    id          varchar(64) PRIMARY KEY,
    name        varchar(64) NOT NULL,
    config_map  jsonb       NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_config_name UNIQUE (name)
);
