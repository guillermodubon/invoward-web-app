CREATE TABLE invoward.analyses
(
    id                         UUID           NOT NULL,
    user_id                    UUID,
    guest_session_id           UUID,
    status                     VARCHAR(40)    NOT NULL,
    review_status              VARCHAR(20)    NOT NULL,
    reconciliation_status      VARCHAR(24),
    supplier_name              VARCHAR(240),
    supplier_key               VARCHAR(240),
    reference_type             VARCHAR(32),
    reference_number           VARCHAR(120),
    invoice_number             VARCHAR(120),
    currency                   VARCHAR(3),
    reference_total            NUMERIC(19, 4),
    invoiced_total             NUMERIC(19, 4),
    difference                 NUMERIC(19, 4),
    price_tolerance_percent    NUMERIC(7, 4)  NOT NULL DEFAULT 0,
    price_tolerance_absolute   NUMERIC(19, 4),
    failure_code               VARCHAR(80),
    failure_user_message       VARCHAR(500),
    retryable                  BOOLEAN        NOT NULL DEFAULT FALSE,
    version                    BIGINT         NOT NULL DEFAULT 0,
    completed_at               TIMESTAMPTZ,
    expires_at                 TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ    NOT NULL,
    updated_at                 TIMESTAMPTZ    NOT NULL,

    CONSTRAINT analyses_pk PRIMARY KEY (id),
    CONSTRAINT analyses_user_fk
        FOREIGN KEY (user_id) REFERENCES invoward.users (id) ON DELETE CASCADE,
    CONSTRAINT analyses_guest_session_fk
        FOREIGN KEY (guest_session_id) REFERENCES invoward.guest_sessions (id) ON DELETE CASCADE,
    CONSTRAINT analyses_owner_xor_ck
        CHECK ((user_id IS NOT NULL AND guest_session_id IS NULL)
            OR (user_id IS NULL AND guest_session_id IS NOT NULL)),
    CONSTRAINT analyses_guest_expiry_ck
        CHECK (guest_session_id IS NULL OR expires_at IS NOT NULL),
    CONSTRAINT analyses_status_ck
        CHECK (status IN (
            'CREATED',
            'UPLOADING',
            'CLASSIFYING',
            'EXTRACTING',
            'AWAITING_CONFIRMATION',
            'MATCHING',
            'AWAITING_MATCH_REVIEW',
            'RECONCILING',
            'GENERATING_REPORT',
            'COMPLETED',
            'FAILED'
        )),
    CONSTRAINT analyses_review_status_ck
        CHECK (review_status IN ('PENDING', 'REVIEWED')),
    CONSTRAINT analyses_reconciliation_status_ck
        CHECK (reconciliation_status IS NULL OR reconciliation_status IN ('MATCHED', 'REVIEW_REQUIRED')),
    CONSTRAINT analyses_reference_type_ck
        CHECK (reference_type IS NULL OR reference_type IN ('QUOTE', 'ESTIMATE', 'PURCHASE_ORDER', 'UNKNOWN')),
    CONSTRAINT analyses_currency_format_ck
        CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT analyses_reference_total_nonnegative_ck
        CHECK (reference_total IS NULL OR reference_total >= 0),
    CONSTRAINT analyses_invoiced_total_nonnegative_ck
        CHECK (invoiced_total IS NULL OR invoiced_total >= 0),
    CONSTRAINT analyses_price_tolerance_percent_range_ck
        CHECK (price_tolerance_percent BETWEEN 0 AND 100),
    CONSTRAINT analyses_price_tolerance_absolute_nonnegative_ck
        CHECK (price_tolerance_absolute IS NULL OR price_tolerance_absolute >= 0),
    CONSTRAINT analyses_version_nonnegative_ck
        CHECK (version >= 0)
);

CREATE TABLE invoward.analysis_jobs
(
    id                UUID         NOT NULL,
    analysis_id       UUID         NOT NULL,
    status            VARCHAR(32)  NOT NULL,
    current_stage     VARCHAR(40)  NOT NULL,
    attempt_count     INTEGER      NOT NULL DEFAULT 0,
    retryable         BOOLEAN      NOT NULL DEFAULT FALSE,
    last_error_code   VARCHAR(80),
    last_error_message VARCHAR(500),
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,

    CONSTRAINT analysis_jobs_pk PRIMARY KEY (id),
    CONSTRAINT analysis_jobs_analysis_uq UNIQUE (analysis_id),
    CONSTRAINT analysis_jobs_analysis_fk
        FOREIGN KEY (analysis_id) REFERENCES invoward.analyses (id) ON DELETE CASCADE,
    CONSTRAINT analysis_jobs_status_ck
        CHECK (status IN ('QUEUED', 'RUNNING', 'WAITING_FOR_USER', 'COMPLETED', 'FAILED')),
    CONSTRAINT analysis_jobs_current_stage_ck
        CHECK (current_stage IN (
            'CREATED',
            'UPLOADING',
            'CLASSIFYING',
            'EXTRACTING',
            'AWAITING_CONFIRMATION',
            'MATCHING',
            'AWAITING_MATCH_REVIEW',
            'RECONCILING',
            'GENERATING_REPORT',
            'COMPLETED',
            'FAILED'
        )),
    CONSTRAINT analysis_jobs_attempt_count_nonnegative_ck
        CHECK (attempt_count >= 0)
);
