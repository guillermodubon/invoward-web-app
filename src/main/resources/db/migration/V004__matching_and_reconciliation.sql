CREATE TABLE invoward.line_item_matches
(
    id                    UUID         NOT NULL,
    analysis_id           UUID         NOT NULL,
    reference_line_item_id UUID,
    invoice_line_item_id   UUID,
    status                VARCHAR(32)  NOT NULL,
    method                VARCHAR(32)  NOT NULL,
    confidence            NUMERIC(5, 4),
    version               BIGINT       NOT NULL DEFAULT 0,
    reviewed_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,

    CONSTRAINT line_item_matches_pk PRIMARY KEY (id),
    CONSTRAINT line_item_matches_analysis_fk
        FOREIGN KEY (analysis_id) REFERENCES invoward.analyses (id) ON DELETE CASCADE,
    CONSTRAINT line_item_matches_reference_line_fk
        FOREIGN KEY (reference_line_item_id)
            REFERENCES invoward.extracted_line_items (id) ON DELETE CASCADE,
    CONSTRAINT line_item_matches_invoice_line_fk
        FOREIGN KEY (invoice_line_item_id)
            REFERENCES invoward.extracted_line_items (id) ON DELETE CASCADE,
    CONSTRAINT line_item_matches_status_ck
        CHECK (status IN ('MATCHED', 'NEEDS_REVIEW', 'UNMATCHED_REFERENCE', 'UNMATCHED_INVOICE')),
    CONSTRAINT line_item_matches_method_ck
        CHECK (method IN ('SKU', 'NORMALIZED_NAME', 'FUZZY', 'AI', 'MANUAL', 'NONE')),
    CONSTRAINT line_item_matches_shape_ck
        CHECK (
            (status IN ('MATCHED', 'NEEDS_REVIEW')
                AND reference_line_item_id IS NOT NULL
                AND invoice_line_item_id IS NOT NULL)
            OR (status = 'UNMATCHED_REFERENCE'
                AND reference_line_item_id IS NOT NULL
                AND invoice_line_item_id IS NULL)
            OR (status = 'UNMATCHED_INVOICE'
                AND reference_line_item_id IS NULL
                AND invoice_line_item_id IS NOT NULL)
        ),
    CONSTRAINT line_item_matches_confidence_range_ck
        CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    CONSTRAINT line_item_matches_version_nonnegative_ck
        CHECK (version >= 0)
);

CREATE TABLE invoward.reconciliation_lines
(
    id                         UUID          NOT NULL,
    analysis_id                UUID          NOT NULL,
    line_match_id              UUID          NOT NULL,
    status                     VARCHAR(24)   NOT NULL,
    reference_item_code        VARCHAR(120),
    invoice_item_code          VARCHAR(120),
    reference_description      TEXT,
    invoice_description        TEXT,
    reference_quantity         NUMERIC(19, 4),
    invoice_quantity           NUMERIC(19, 4),
    reference_unit_price       NUMERIC(19, 4),
    invoice_unit_price         NUMERIC(19, 4),
    reference_line_total       NUMERIC(19, 4),
    invoice_line_total         NUMERIC(19, 4),
    quantity_difference        NUMERIC(19, 4),
    unit_price_difference      NUMERIC(19, 4),
    line_total_difference      NUMERIC(19, 4),
    created_at                 TIMESTAMPTZ   NOT NULL,

    CONSTRAINT reconciliation_lines_pk PRIMARY KEY (id),
    CONSTRAINT reconciliation_lines_line_match_uq UNIQUE (line_match_id),
    CONSTRAINT reconciliation_lines_analysis_fk
        FOREIGN KEY (analysis_id) REFERENCES invoward.analyses (id) ON DELETE CASCADE,
    CONSTRAINT reconciliation_lines_line_match_fk
        FOREIGN KEY (line_match_id) REFERENCES invoward.line_item_matches (id) ON DELETE CASCADE,
    CONSTRAINT reconciliation_lines_status_ck
        CHECK (status IN ('MATCHED', 'DIFFERENT', 'MISSING', 'UNEXPECTED'))
);

CREATE TABLE invoward.discrepancies
(
    id                    UUID          NOT NULL,
    analysis_id           UUID          NOT NULL,
    reconciliation_line_id UUID,
    type                  VARCHAR(40)   NOT NULL,
    reference_value       VARCHAR(255),
    invoice_value         VARCHAR(255),
    absolute_difference   NUMERIC(19, 4),
    percentage_difference NUMERIC(19, 6),
    explanation           TEXT          NOT NULL,
    resolution_status     VARCHAR(24)   NOT NULL DEFAULT 'OPEN',
    resolution_reason     VARCHAR(48),
    resolution_note       TEXT,
    version               BIGINT        NOT NULL DEFAULT 0,
    resolved_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,

    CONSTRAINT discrepancies_pk PRIMARY KEY (id),
    CONSTRAINT discrepancies_analysis_fk
        FOREIGN KEY (analysis_id) REFERENCES invoward.analyses (id) ON DELETE CASCADE,
    CONSTRAINT discrepancies_reconciliation_line_fk
        FOREIGN KEY (reconciliation_line_id)
            REFERENCES invoward.reconciliation_lines (id) ON DELETE CASCADE,
    CONSTRAINT discrepancies_type_ck
        CHECK (type IN (
            'QUANTITY_MISMATCH',
            'UNIT_PRICE_MISMATCH',
            'MISSING_ITEM',
            'UNEXPECTED_ITEM',
            'DISCOUNT_MISMATCH',
            'TAX_MISMATCH',
            'SUBTOTAL_MISMATCH',
            'TOTAL_MISMATCH',
            'CURRENCY_MISMATCH'
        )),
    CONSTRAINT discrepancies_resolution_status_ck
        CHECK (resolution_status IN ('OPEN', 'ACCEPTED', 'RESOLVED')),
    CONSTRAINT discrepancies_resolution_reason_ck
        CHECK (resolution_reason IS NULL OR resolution_reason IN (
            'NEEDS_INVESTIGATION',
            'APPROVED_CHANGE',
            'SUPPLIER_CORRECTION_EXPECTED',
            'OTHER'
        )),
    CONSTRAINT discrepancies_explanation_nonblank_ck
        CHECK (BTRIM(explanation) <> ''),
    CONSTRAINT discrepancies_open_resolved_at_ck
        CHECK (resolution_status <> 'OPEN' OR resolved_at IS NULL),
    CONSTRAINT discrepancies_closed_resolution_details_ck
        CHECK (
            resolution_status = 'OPEN'
            OR (resolution_reason IS NOT NULL AND resolved_at IS NOT NULL)
        ),
    CONSTRAINT discrepancies_other_resolution_note_ck
        CHECK (
            resolution_reason IS DISTINCT FROM 'OTHER'
            OR (resolution_note IS NOT NULL AND BTRIM(resolution_note) <> '')
        ),
    CONSTRAINT discrepancies_version_nonnegative_ck
        CHECK (version >= 0)
);

CREATE TABLE invoward.discrepancy_evidence
(
    id             UUID         NOT NULL,
    discrepancy_id UUID         NOT NULL,
    document_id    UUID         NOT NULL,
    side           VARCHAR(16)  NOT NULL,
    page_number    INTEGER      NOT NULL,
    source_text    TEXT,
    bounding_box   JSONB,
    created_at     TIMESTAMPTZ  NOT NULL,

    CONSTRAINT discrepancy_evidence_pk PRIMARY KEY (id),
    CONSTRAINT discrepancy_evidence_discrepancy_fk
        FOREIGN KEY (discrepancy_id) REFERENCES invoward.discrepancies (id) ON DELETE CASCADE,
    CONSTRAINT discrepancy_evidence_document_fk
        FOREIGN KEY (document_id) REFERENCES invoward.documents (id) ON DELETE CASCADE,
    CONSTRAINT discrepancy_evidence_side_ck
        CHECK (side IN ('REFERENCE', 'INVOICE')),
    CONSTRAINT discrepancy_evidence_page_number_positive_ck
        CHECK (page_number > 0),
    CONSTRAINT discrepancy_evidence_bounding_box_object_ck
        CHECK (bounding_box IS NULL OR JSONB_TYPEOF(bounding_box) = 'object')
);
