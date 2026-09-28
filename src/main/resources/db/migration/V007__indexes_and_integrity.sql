CREATE UNIQUE INDEX users_email_lower_uq
    ON invoward.users (LOWER(email));

CREATE INDEX email_verification_tokens_user_purpose_created_idx
    ON invoward.email_verification_tokens (user_id, purpose, created_at DESC);
CREATE INDEX email_verification_tokens_expires_at_idx
    ON invoward.email_verification_tokens (expires_at);

CREATE INDEX password_reset_tokens_user_created_idx
    ON invoward.password_reset_tokens (user_id, created_at DESC);
CREATE INDEX password_reset_tokens_expires_at_idx
    ON invoward.password_reset_tokens (expires_at);

CREATE INDEX guest_sessions_expires_at_idx
    ON invoward.guest_sessions (expires_at);

CREATE INDEX analyses_user_created_idx
    ON invoward.analyses (user_id, created_at DESC);
CREATE INDEX analyses_guest_session_created_idx
    ON invoward.analyses (guest_session_id, created_at DESC);
CREATE INDEX analyses_user_status_created_idx
    ON invoward.analyses (user_id, status, created_at DESC);
CREATE INDEX analyses_user_review_status_created_idx
    ON invoward.analyses (user_id, review_status, created_at DESC);
CREATE INDEX analyses_user_supplier_key_created_idx
    ON invoward.analyses (user_id, supplier_key, created_at DESC);
CREATE INDEX analyses_user_invoice_number_idx
    ON invoward.analyses (user_id, invoice_number);
CREATE INDEX analyses_user_reference_number_idx
    ON invoward.analyses (user_id, reference_number);
CREATE INDEX analyses_user_difference_idx
    ON invoward.analyses (user_id, difference);
CREATE INDEX analyses_expires_at_idx
    ON invoward.analyses (expires_at);

CREATE INDEX analysis_jobs_status_updated_idx
    ON invoward.analysis_jobs (status, updated_at);

CREATE UNIQUE INDEX documents_one_reference_per_analysis_uq
    ON invoward.documents (analysis_id)
    WHERE role = 'REFERENCE';
CREATE UNIQUE INDEX documents_one_invoice_per_analysis_uq
    ON invoward.documents (analysis_id)
    WHERE role = 'INVOICE';
CREATE INDEX documents_analysis_idx
    ON invoward.documents (analysis_id);
CREATE INDEX documents_sha256_idx
    ON invoward.documents (sha256);

CREATE INDEX extracted_line_items_extracted_document_idx
    ON invoward.extracted_line_items (extracted_document_id);
CREATE INDEX extracted_line_items_item_code_idx
    ON invoward.extracted_line_items (item_code);

CREATE INDEX extraction_cache_entries_sha256_idx
    ON invoward.extraction_cache_entries (sha256);
CREATE INDEX extraction_cache_entries_expires_at_idx
    ON invoward.extraction_cache_entries (expires_at);

CREATE UNIQUE INDEX line_item_matches_reference_line_item_uq
    ON invoward.line_item_matches (reference_line_item_id)
    WHERE reference_line_item_id IS NOT NULL;
CREATE UNIQUE INDEX line_item_matches_invoice_line_item_uq
    ON invoward.line_item_matches (invoice_line_item_id)
    WHERE invoice_line_item_id IS NOT NULL;
CREATE INDEX line_item_matches_analysis_status_idx
    ON invoward.line_item_matches (analysis_id, status);

CREATE INDEX reconciliation_lines_analysis_status_idx
    ON invoward.reconciliation_lines (analysis_id, status);

CREATE INDEX discrepancies_analysis_type_idx
    ON invoward.discrepancies (analysis_id, type);
CREATE INDEX discrepancies_analysis_resolution_status_idx
    ON invoward.discrepancies (analysis_id, resolution_status);

CREATE INDEX discrepancy_evidence_discrepancy_idx
    ON invoward.discrepancy_evidence (discrepancy_id);
CREATE INDEX discrepancy_evidence_document_idx
    ON invoward.discrepancy_evidence (document_id);

CREATE INDEX share_links_analysis_created_idx
    ON invoward.share_links (analysis_id, created_at DESC);
CREATE INDEX share_links_expires_at_idx
    ON invoward.share_links (expires_at);
