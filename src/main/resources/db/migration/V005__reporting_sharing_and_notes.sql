CREATE TABLE invoward.generated_reports
(
    id           UUID         NOT NULL,
    analysis_id  UUID         NOT NULL,
    storage_key  VARCHAR(512) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    sha256       VARCHAR(64)  NOT NULL,
    expires_at   TIMESTAMPTZ,
    generated_at TIMESTAMPTZ  NOT NULL,

    CONSTRAINT generated_reports_pk PRIMARY KEY (id),
    CONSTRAINT generated_reports_analysis_uq UNIQUE (analysis_id),
    CONSTRAINT generated_reports_storage_key_uq UNIQUE (storage_key),
    CONSTRAINT generated_reports_analysis_fk
        FOREIGN KEY (analysis_id) REFERENCES invoward.analyses (id) ON DELETE CASCADE,
    CONSTRAINT generated_reports_storage_key_nonblank_ck
        CHECK (BTRIM(storage_key) <> ''),
    CONSTRAINT generated_reports_content_type_nonblank_ck
        CHECK (BTRIM(content_type) <> ''),
    CONSTRAINT generated_reports_size_bytes_positive_ck
        CHECK (size_bytes > 0),
    CONSTRAINT generated_reports_sha256_format_ck
        CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE invoward.share_links
(
    id         UUID        NOT NULL,
    analysis_id UUID       NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT share_links_pk PRIMARY KEY (id),
    CONSTRAINT share_links_token_hash_uq UNIQUE (token_hash),
    CONSTRAINT share_links_analysis_fk
        FOREIGN KEY (analysis_id) REFERENCES invoward.analyses (id) ON DELETE CASCADE,
    CONSTRAINT share_links_token_hash_format_ck
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT share_links_expiration_ck
        CHECK (expires_at > created_at),
    CONSTRAINT share_links_revoked_at_ck
        CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE TABLE invoward.analysis_notes
(
    id          UUID        NOT NULL,
    analysis_id UUID        NOT NULL,
    note_text   TEXT        NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT analysis_notes_pk PRIMARY KEY (id),
    CONSTRAINT analysis_notes_analysis_uq UNIQUE (analysis_id),
    CONSTRAINT analysis_notes_analysis_fk
        FOREIGN KEY (analysis_id) REFERENCES invoward.analyses (id) ON DELETE CASCADE,
    CONSTRAINT analysis_notes_note_text_nonblank_ck
        CHECK (BTRIM(note_text) <> ''),
    CONSTRAINT analysis_notes_note_text_length_ck
        CHECK (CHAR_LENGTH(note_text) <= 5000),
    CONSTRAINT analysis_notes_version_nonnegative_ck
        CHECK (version >= 0)
);
