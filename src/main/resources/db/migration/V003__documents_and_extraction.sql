CREATE TABLE invoward.documents
(
    id                UUID         NOT NULL,
    analysis_id       UUID         NOT NULL,
    role              VARCHAR(20)  NOT NULL,
    detected_type     VARCHAR(32),
    confirmed_type    VARCHAR(32),
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    page_count        INTEGER,
    sha256            VARCHAR(64)  NOT NULL,
    storage_key       VARCHAR(512) NOT NULL,
    expires_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL,

    CONSTRAINT documents_pk PRIMARY KEY (id),
    CONSTRAINT documents_analysis_fk
        FOREIGN KEY (analysis_id) REFERENCES invoward.analyses (id) ON DELETE CASCADE,
    CONSTRAINT documents_storage_key_uq UNIQUE (storage_key),
    CONSTRAINT documents_role_ck
        CHECK (role IN ('REFERENCE', 'INVOICE')),
    CONSTRAINT documents_detected_type_ck
        CHECK (detected_type IS NULL OR detected_type IN (
            'QUOTE', 'ESTIMATE', 'PURCHASE_ORDER', 'INVOICE', 'UNKNOWN'
        )),
    CONSTRAINT documents_confirmed_type_ck
        CHECK (confirmed_type IS NULL OR confirmed_type IN (
            'QUOTE', 'ESTIMATE', 'PURCHASE_ORDER', 'INVOICE', 'UNKNOWN'
        )),
    CONSTRAINT documents_confirmed_type_role_ck
        CHECK (
            confirmed_type IS NULL
            OR (role = 'REFERENCE' AND confirmed_type IN (
                'QUOTE', 'ESTIMATE', 'PURCHASE_ORDER', 'UNKNOWN'
            ))
            OR (role = 'INVOICE' AND confirmed_type IN ('INVOICE', 'UNKNOWN'))
        ),
    CONSTRAINT documents_original_filename_nonblank_ck
        CHECK (BTRIM(original_filename) <> ''),
    CONSTRAINT documents_content_type_nonblank_ck
        CHECK (BTRIM(content_type) <> ''),
    CONSTRAINT documents_size_bytes_positive_ck
        CHECK (size_bytes > 0),
    CONSTRAINT documents_page_count_positive_ck
        CHECK (page_count IS NULL OR page_count > 0),
    CONSTRAINT documents_sha256_format_ck
        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT documents_storage_key_nonblank_ck
        CHECK (BTRIM(storage_key) <> '')
);

CREATE TABLE invoward.extracted_documents
(
    id                UUID          NOT NULL,
    document_id       UUID          NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    extraction_source VARCHAR(20)   NOT NULL,
    vendor_name       VARCHAR(240),
    document_number   VARCHAR(120),
    document_date     DATE,
    currency          VARCHAR(3),
    subtotal          NUMERIC(19, 4),
    discount_total    NUMERIC(19, 4),
    tax_total         NUMERIC(19, 4),
    total             NUMERIC(19, 4),
    extractor_version VARCHAR(50)   NOT NULL,
    model_id          VARCHAR(120),
    schema_version    INTEGER       NOT NULL,
    version           BIGINT        NOT NULL DEFAULT 0,
    extracted_at      TIMESTAMPTZ   NOT NULL,
    confirmed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,

    CONSTRAINT extracted_documents_pk PRIMARY KEY (id),
    CONSTRAINT extracted_documents_document_uq UNIQUE (document_id),
    CONSTRAINT extracted_documents_document_fk
        FOREIGN KEY (document_id) REFERENCES invoward.documents (id) ON DELETE CASCADE,
    CONSTRAINT extracted_documents_status_ck
        CHECK (status IN ('DRAFT', 'CONFIRMED')),
    CONSTRAINT extracted_documents_source_ck
        CHECK (extraction_source IN ('AI', 'CACHE')),
    CONSTRAINT extracted_documents_currency_format_ck
        CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT extracted_documents_subtotal_nonnegative_ck
        CHECK (subtotal IS NULL OR subtotal >= 0),
    CONSTRAINT extracted_documents_discount_total_nonnegative_ck
        CHECK (discount_total IS NULL OR discount_total >= 0),
    CONSTRAINT extracted_documents_tax_total_nonnegative_ck
        CHECK (tax_total IS NULL OR tax_total >= 0),
    CONSTRAINT extracted_documents_total_nonnegative_ck
        CHECK (total IS NULL OR total >= 0),
    CONSTRAINT extracted_documents_extractor_version_nonblank_ck
        CHECK (BTRIM(extractor_version) <> ''),
    CONSTRAINT extracted_documents_schema_version_positive_ck
        CHECK (schema_version >= 1),
    CONSTRAINT extracted_documents_version_nonnegative_ck
        CHECK (version >= 0),
    CONSTRAINT extracted_documents_confirmation_timestamp_ck
        CHECK (
            (status = 'CONFIRMED' AND confirmed_at IS NOT NULL)
            OR (status = 'DRAFT' AND confirmed_at IS NULL)
        )
);

CREATE TABLE invoward.extracted_line_items
(
    id                    UUID          NOT NULL,
    extracted_document_id UUID          NOT NULL,
    line_position         INTEGER       NOT NULL,
    item_code             VARCHAR(120),
    description           TEXT          NOT NULL,
    normalized_description TEXT,
    quantity              NUMERIC(19, 4),
    unit                  VARCHAR(50),
    unit_price            NUMERIC(19, 4),
    discount_amount       NUMERIC(19, 4),
    tax_amount            NUMERIC(19, 4),
    line_total            NUMERIC(19, 4),
    page_number           INTEGER,
    source_text           TEXT,
    bounding_box          JSONB,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,

    CONSTRAINT extracted_line_items_pk PRIMARY KEY (id),
    CONSTRAINT extracted_line_items_document_position_uq
        UNIQUE (extracted_document_id, line_position),
    CONSTRAINT extracted_line_items_document_fk
        FOREIGN KEY (extracted_document_id)
            REFERENCES invoward.extracted_documents (id) ON DELETE CASCADE,
    CONSTRAINT extracted_line_items_position_nonnegative_ck
        CHECK (line_position >= 0),
    CONSTRAINT extracted_line_items_description_nonblank_ck
        CHECK (BTRIM(description) <> ''),
    CONSTRAINT extracted_line_items_quantity_positive_ck
        CHECK (quantity IS NULL OR quantity > 0),
    CONSTRAINT extracted_line_items_unit_price_nonnegative_ck
        CHECK (unit_price IS NULL OR unit_price >= 0),
    CONSTRAINT extracted_line_items_discount_amount_nonnegative_ck
        CHECK (discount_amount IS NULL OR discount_amount >= 0),
    CONSTRAINT extracted_line_items_tax_amount_nonnegative_ck
        CHECK (tax_amount IS NULL OR tax_amount >= 0),
    CONSTRAINT extracted_line_items_line_total_nonnegative_ck
        CHECK (line_total IS NULL OR line_total >= 0),
    CONSTRAINT extracted_line_items_page_number_positive_ck
        CHECK (page_number IS NULL OR page_number > 0),
    CONSTRAINT extracted_line_items_bounding_box_object_ck
        CHECK (bounding_box IS NULL OR JSONB_TYPEOF(bounding_box) = 'object')
);
