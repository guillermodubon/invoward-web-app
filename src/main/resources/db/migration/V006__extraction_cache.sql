CREATE TABLE invoward.extraction_cache_entries
(
    id               UUID         NOT NULL,
    sha256           VARCHAR(64)  NOT NULL,
    extractor_version VARCHAR(50)  NOT NULL,
    model_id         VARCHAR(120) NOT NULL,
    schema_version   INTEGER      NOT NULL,
    payload          JSONB        NOT NULL,
    expires_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT extraction_cache_entries_pk PRIMARY KEY (id),
    CONSTRAINT extraction_cache_entries_key_uq
        UNIQUE (sha256, extractor_version, model_id, schema_version),
    CONSTRAINT extraction_cache_entries_sha256_format_ck
        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT extraction_cache_entries_extractor_version_nonblank_ck
        CHECK (BTRIM(extractor_version) <> ''),
    CONSTRAINT extraction_cache_entries_model_id_nonblank_ck
        CHECK (BTRIM(model_id) <> ''),
    CONSTRAINT extraction_cache_entries_schema_version_positive_ck
        CHECK (schema_version >= 1),
    CONSTRAINT extraction_cache_entries_payload_object_ck
        CHECK (JSONB_TYPEOF(payload) = 'object')
);
