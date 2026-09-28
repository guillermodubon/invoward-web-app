package io.github.guillermodubon.invoward.persistence;

import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.UUID;

import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.LinePair;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.countRows;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertAnalysisNote;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertCacheEntry;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertDiscrepancy;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertEvidence;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertGeneratedReport;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertGuestSession;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertAnalysis;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertMatch;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertReconciliationLine;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertReferenceAndInvoiceLines;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertShareLink;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertUser;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertUserAnalysis;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.sha256;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CascadeAndCacheIntegrityIT extends PostgresIntegrityTestSupport {

    @Test
    void deletingAnalysisRemovesItsRelationalChildren() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO invoward.analysis_jobs
                        (id, analysis_id, status, current_stage, created_at, updated_at)
                    VALUES (?, ?, 'QUEUED', 'CREATED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, analysisId);
                statement.executeUpdate();
            }

            LinePair lines = insertReferenceAndInvoiceLines(connection, analysisId);
            UUID matchId = insertMatch(connection, analysisId,
                    lines.referenceLineId(), lines.invoiceLineId(), "MATCHED", "MANUAL", null);
            UUID reconciliationLineId = insertReconciliationLine(
                    connection, analysisId, matchId, "DIFFERENT");
            UUID discrepancyId = insertDiscrepancy(connection, analysisId, reconciliationLineId,
                    "TOTAL_MISMATCH", "OPEN", null, null, false);
            insertEvidence(connection, discrepancyId, lines.invoiceDocumentId(), 1);
            insertGeneratedReport(connection, analysisId, "reports/cascade.pdf");
            insertShareLink(connection, analysisId, "d".repeat(64), true);
            insertAnalysisNote(connection, analysisId, "Cascade test note");

            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM invoward.analyses WHERE id = ?")) {
                statement.setObject(1, analysisId);
                assertEquals(1, statement.executeUpdate());
            }

            for (String table : new String[]{
                    "analysis_jobs", "documents", "extracted_documents", "extracted_line_items",
                    "line_item_matches", "reconciliation_lines", "discrepancies",
                    "discrepancy_evidence", "generated_reports", "share_links", "analysis_notes"}) {
                assertEquals(0, countRows(connection, table), table + " should cascade from analysis");
            }
        });
    }

    @Test
    void deletingGuestSessionRemovesGuestOwnedAnalyses() throws Exception {
        inTransaction(connection -> {
            UUID guestId = insertGuestSession(connection);
            insertAnalysis(connection, null, guestId, true);

            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM invoward.guest_sessions WHERE id = ?")) {
                statement.setObject(1, guestId);
                assertEquals(1, statement.executeUpdate());
            }

            assertEquals(0, countRows(connection, "analyses"));
            assertEquals(0, countRows(connection, "guest_sessions"));
        });
    }

    @Test
    void deletingUserRemovesUserOwnedAnalyses() throws Exception {
        inTransaction(connection -> {
            UUID userId = insertUser(connection);
            insertAnalysis(connection, userId, null, false);

            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM invoward.users WHERE id = ?")) {
                statement.setObject(1, userId);
                assertEquals(1, statement.executeUpdate());
            }

            assertEquals(0, countRows(connection, "analyses"));
        });
    }

    @Test
    void extractionCacheKeyTupleIsUnique() throws Exception {
        inTransaction(connection -> {
            String hash = sha256();
            insertCacheEntry(connection, hash, "extractor-v1", "model-v1", 1, "{}");
            assertRejected(connection, c -> insertCacheEntry(
                    c, hash, "extractor-v1", "model-v1", 1, "{\"other\":true}"));
        });
    }

    @Test
    void extractionCachePayloadMustBeAJsonObject() throws Exception {
        inTransaction(connection -> {
            assertRejected(connection, c -> insertCacheEntry(
                    c, sha256(), "extractor-v1", "model-v1", 1, "[]"));
            assertRejected(connection, c -> insertCacheEntry(
                    c, sha256(), "extractor-v1", "model-v1", 1, "\"scalar\""));
        });
    }
}
