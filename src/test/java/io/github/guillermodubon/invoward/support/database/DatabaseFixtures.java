package io.github.guillermodubon.invoward.support.database;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

/** Shared, disposable rows for PostgreSQL integrity tests. */
public final class DatabaseFixtures {

    private DatabaseFixtures() {
    }

    public static UUID insertUser(Connection connection) throws SQLException {
        return insertUser(connection, "user-" + UUID.randomUUID() + "@example.test", "ACTIVE");
    }

    public static UUID insertUser(Connection connection, String email, String status)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO invoward.users (id, display_name, email, password_hash, status)
                VALUES (?, 'Integrity Test', ?, 'test-password-hash', ?)
                """, id, email, status);
        return id;
    }

    public static UUID insertEmailVerificationToken(
            Connection connection, UUID userId, String tokenHash) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO invoward.email_verification_tokens
                    (id, user_id, token_hash, purpose, target_email, expires_at, created_at)
                VALUES (?, ?, ?, 'REGISTRATION', 'target@example.test',
                    CURRENT_TIMESTAMP + INTERVAL '1 day', CURRENT_TIMESTAMP)
                """, id, userId, tokenHash);
        return id;
    }

    public static UUID insertPasswordResetToken(
            Connection connection, UUID userId, String tokenHash) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO invoward.password_reset_tokens
                    (id, user_id, token_hash, expires_at, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '1 day', CURRENT_TIMESTAMP)
                """, id, userId, tokenHash);
        return id;
    }

    public static UUID insertGuestSession(Connection connection) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO invoward.guest_sessions (id, expires_at, last_seen_at, created_at)
                VALUES (?, CURRENT_TIMESTAMP + INTERVAL '1 day', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id);
        return id;
    }

    public static UUID insertAnalysis(
            Connection connection, UUID userId, UUID guestSessionId, boolean withExpiry)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO invoward.analyses
                    (id, user_id, guest_session_id, status, review_status, expires_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, 'CREATED', 'PENDING',
                    CASE WHEN ? THEN CURRENT_TIMESTAMP + INTERVAL '1 day' ELSE NULL END,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, userId, guestSessionId, withExpiry);
        return id;
    }

    public static UUID insertUserAnalysis(Connection connection) throws SQLException {
        return insertAnalysis(connection, insertUser(connection), null, false);
    }

    public static UUID insertGuestAnalysis(Connection connection) throws SQLException {
        return insertAnalysis(connection, null, insertGuestSession(connection), true);
    }

    public static UUID insertDocument(
            Connection connection,
            UUID analysisId,
            String role,
            String confirmedType,
            String storageKey) throws SQLException {
        return insertDocument(connection, analysisId, role, confirmedType, storageKey,
                sha256(), 1L, null);
    }

    public static UUID insertDocument(
            Connection connection,
            UUID analysisId,
            String role,
            String confirmedType,
            String storageKey,
            String sha256,
            long sizeBytes,
            Integer pageCount) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO invoward.documents
                    (id, analysis_id, role, confirmed_type, original_filename, content_type,
                     size_bytes, page_count, sha256, storage_key, created_at)
                VALUES (?, ?, ?, ?, 'invoice.pdf', 'application/pdf', ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, id, analysisId, role, confirmedType, sizeBytes, pageCount, sha256, storageKey);
        return id;
    }

    public static UUID insertExtractedDocument(
            Connection connection, UUID documentId, String status) throws SQLException {
        return insertExtractedDocument(connection, documentId, status,
                null, null, null, null, null);
    }

    public static UUID insertExtractedDocument(
            Connection connection,
            UUID documentId,
            String status,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal taxTotal,
            BigDecimal total,
            Boolean includeConfirmationTimestamp) throws SQLException {
        UUID id = UUID.randomUUID();
        boolean confirmedAt = includeConfirmationTimestamp != null
                ? includeConfirmationTimestamp
                : "CONFIRMED".equals(status);
        execute(connection, """
                INSERT INTO invoward.extracted_documents
                    (id, document_id, status, extraction_source, subtotal, discount_total,
                     tax_total, total, extractor_version, schema_version, extracted_at,
                     confirmed_at, created_at, updated_at)
                VALUES (?, ?, ?, 'AI', ?, ?, ?, ?, 'integrity-test-v1', 1,
                    CURRENT_TIMESTAMP,
                    CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, documentId, status, subtotal, discountTotal, taxTotal, total, confirmedAt);
        return id;
    }

    public static UUID insertLineItem(
            Connection connection, UUID extractedDocumentId, int position) throws SQLException {
        return insertLineItem(connection, extractedDocumentId, position,
                "Test item", null, null, null, null, null, null, null);
    }

    public static UUID insertLineItem(
            Connection connection,
            UUID extractedDocumentId,
            int position,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discountAmount,
            BigDecimal taxAmount,
            BigDecimal lineTotal,
            Integer pageNumber,
            String boundingBoxJson) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO invoward.extracted_line_items
                    (id, extracted_document_id, line_position, description, quantity, unit_price,
                     discount_amount, tax_amount, line_total, page_number, bounding_box,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, extractedDocumentId, position, description, quantity, unitPrice,
                discountAmount, taxAmount, lineTotal, pageNumber, boundingBoxJson);
        return id;
    }

    public static LinePair insertReferenceAndInvoiceLines(
            Connection connection, UUID analysisId) throws SQLException {
        UUID referenceDocument = insertDocument(
                connection, analysisId, "REFERENCE", "QUOTE", "reference-" + UUID.randomUUID());
        UUID invoiceDocument = insertDocument(
                connection, analysisId, "INVOICE", "INVOICE", "invoice-" + UUID.randomUUID());
        UUID referenceExtraction = insertExtractedDocument(connection, referenceDocument, "DRAFT");
        UUID invoiceExtraction = insertExtractedDocument(connection, invoiceDocument, "DRAFT");
        return new LinePair(
                referenceDocument,
                invoiceDocument,
                referenceExtraction,
                invoiceExtraction,
                insertLineItem(connection, referenceExtraction, 0),
                insertLineItem(connection, invoiceExtraction, 0));
    }

    public static UUID insertMatch(
            Connection connection,
            UUID analysisId,
            UUID referenceLineId,
            UUID invoiceLineId,
            String status,
            String method,
            BigDecimal confidence) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO invoward.line_item_matches
                    (id, analysis_id, reference_line_item_id, invoice_line_item_id,
                     status, method, confidence, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, analysisId, referenceLineId, invoiceLineId, status, method, confidence);
        return id;
    }

    public static UUID insertReconciliationLine(
            Connection connection, UUID analysisId, UUID matchId, String status)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO invoward.reconciliation_lines
                    (id, analysis_id, line_match_id, status, created_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, id, analysisId, matchId, status);
        return id;
    }

    public static UUID insertDiscrepancy(
            Connection connection,
            UUID analysisId,
            UUID reconciliationLineId,
            String type,
            String resolutionStatus,
            String resolutionReason,
            String resolutionNote,
            boolean includeResolvedAt) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO invoward.discrepancies
                    (id, analysis_id, reconciliation_line_id, type, explanation,
                     resolution_status, resolution_reason, resolution_note, resolved_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Integrity test discrepancy', ?, ?, ?,
                    CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, analysisId, reconciliationLineId, type, resolutionStatus,
                resolutionReason, resolutionNote, includeResolvedAt);
        return id;
    }

    public static UUID insertEvidence(
            Connection connection, UUID discrepancyId, UUID documentId, int pageNumber)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO invoward.discrepancy_evidence
                    (id, discrepancy_id, document_id, side, page_number, created_at)
                VALUES (?, ?, ?, 'REFERENCE', ?, CURRENT_TIMESTAMP)
                """, id, discrepancyId, documentId, pageNumber);
        return id;
    }

    public static UUID insertGeneratedReport(
            Connection connection, UUID analysisId, String storageKey) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO invoward.generated_reports
                    (id, analysis_id, storage_key, content_type, size_bytes, sha256, generated_at)
                VALUES (?, ?, ?, 'application/pdf', 100, ?, CURRENT_TIMESTAMP)
                """, id, analysisId, storageKey, sha256());
        return id;
    }

    public static UUID insertShareLink(
            Connection connection, UUID analysisId, String tokenHash, boolean validExpiry)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO invoward.share_links
                    (id, analysis_id, token_hash, expires_at, created_at)
                VALUES (?, ?, ?,
                    CASE WHEN ? THEN CURRENT_TIMESTAMP + INTERVAL '1 day' ELSE CURRENT_TIMESTAMP END,
                    CURRENT_TIMESTAMP)
                """, id, analysisId, tokenHash, validExpiry);
        return id;
    }

    public static UUID insertAnalysisNote(
            Connection connection, UUID analysisId, String note) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO invoward.analysis_notes
                    (id, analysis_id, note_text, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, analysisId, note);
        return id;
    }

    public static UUID insertCacheEntry(
            Connection connection,
            String documentHash,
            String extractorVersion,
            String modelId,
            int schemaVersion,
            String payloadJson) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, """
                INSERT INTO invoward.extraction_cache_entries
                    (id, sha256, extractor_version, model_id, schema_version, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP)
                """, id, documentHash, extractorVersion, modelId, schemaVersion, payloadJson);
        return id;
    }

    public static long countRows(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM invoward." + table);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    public static String sha256() {
        String value = UUID.randomUUID().toString().replace("-", "");
        return value + value;
    }

    private static void execute(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                Object parameter = parameters[index];
                if (parameter == null) {
                    statement.setNull(index + 1, Types.NULL);
                } else {
                    statement.setObject(index + 1, parameter);
                }
            }
            statement.executeUpdate();
        }
    }

    public record LinePair(
            UUID referenceDocumentId,
            UUID invoiceDocumentId,
            UUID referenceExtractionId,
            UUID invoiceExtractionId,
            UUID referenceLineId,
            UUID invoiceLineId) {
    }
}
