CREATE SCHEMA IF NOT EXISTS invoward;

CREATE TABLE invoward.users
(
    id             UUID         NOT NULL,
    display_name   VARCHAR(120) NOT NULL,
    email          VARCHAR(320) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    email_verified BOOLEAN      NOT NULL DEFAULT FALSE,
    status         VARCHAR(32)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT users_pk PRIMARY KEY (id),
    CONSTRAINT users_display_name_trimmed_nonblank_ck
        CHECK (display_name = BTRIM(display_name) AND display_name <> ''),
    CONSTRAINT users_password_hash_nonblank_ck
        CHECK (BTRIM(password_hash) <> ''),
    CONSTRAINT users_status_ck
        CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED'))
);

CREATE TABLE invoward.email_verification_tokens
(
    id          UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL,
    purpose     VARCHAR(32)  NOT NULL,
    target_email VARCHAR(320) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT email_verification_tokens_pk PRIMARY KEY (id),
    CONSTRAINT email_verification_tokens_user_fk
        FOREIGN KEY (user_id) REFERENCES invoward.users (id) ON DELETE CASCADE,
    CONSTRAINT email_verification_tokens_token_hash_uq UNIQUE (token_hash),
    CONSTRAINT email_verification_tokens_hash_format_ck
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT email_verification_tokens_purpose_ck
        CHECK (purpose IN ('REGISTRATION', 'EMAIL_CHANGE')),
    CONSTRAINT email_verification_tokens_expiry_ck
        CHECK (expires_at > created_at),
    CONSTRAINT email_verification_tokens_used_at_ck
        CHECK (used_at IS NULL OR used_at >= created_at)
);

CREATE TABLE invoward.password_reset_tokens
(
    id         UUID        NOT NULL,
    user_id    UUID        NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT password_reset_tokens_pk PRIMARY KEY (id),
    CONSTRAINT password_reset_tokens_user_fk
        FOREIGN KEY (user_id) REFERENCES invoward.users (id) ON DELETE CASCADE,
    CONSTRAINT password_reset_tokens_token_hash_uq UNIQUE (token_hash),
    CONSTRAINT password_reset_tokens_hash_format_ck
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT password_reset_tokens_expiry_ck
        CHECK (expires_at > created_at),
    CONSTRAINT password_reset_tokens_used_at_ck
        CHECK (used_at IS NULL OR used_at >= created_at)
);

CREATE TABLE invoward.guest_sessions
(
    id           UUID        NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,

    CONSTRAINT guest_sessions_pk PRIMARY KEY (id),
    CONSTRAINT guest_sessions_expiry_ck
        CHECK (expires_at > created_at),
    CONSTRAINT guest_sessions_last_seen_ck
        CHECK (last_seen_at >= created_at)
);
