package io.github.guillermodubon.invoward.persistence;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.UUID;

import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.LinePair;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.countRows;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertDiscrepancy;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertEvidence;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertMatch;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertReconciliationLine;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertLineItem;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertReferenceAndInvoiceLines;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertUserAnalysis;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MatchingAndReconciliationIntegrityIT extends PostgresIntegrityTestSupport {

    @Test
    void matchedAndNeedsReviewRowsRequireBothLineSides() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            LinePair lines = insertReferenceAndInvoiceLines(connection, analysisId);
            assertRejected(connection, c -> insertMatch(c, analysisId,
                    lines.referenceLineId(), null, "MATCHED", "MANUAL", null));
            assertRejected(connection, c -> insertMatch(c, analysisId,
                    null, lines.invoiceLineId(), "NEEDS_REVIEW", "AI", null));
        });
    }

    @Test
    void unmatchedStatusesEnforceTheirOneSidedShape() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            LinePair lines = insertReferenceAndInvoiceLines(connection, analysisId);
            assertRejected(connection, c -> insertMatch(c, analysisId,
                    lines.referenceLineId(), lines.invoiceLineId(),
                    "UNMATCHED_REFERENCE", "NONE", null));
            assertRejected(connection, c -> insertMatch(c, analysisId,
                    lines.referenceLineId(), lines.invoiceLineId(),
                    "UNMATCHED_INVOICE", "NONE", null));

            insertMatch(connection, analysisId,
                    lines.referenceLineId(), null, "UNMATCHED_REFERENCE", "NONE", null);
            insertMatch(connection, analysisId,
                    null, lines.invoiceLineId(), "UNMATCHED_INVOICE", "NONE", null);
            assertEquals(2, countRows(connection, "line_item_matches"));
        });
    }

    @Test
    void matchConfidenceMustBeBetweenZeroAndOne() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            LinePair lines = insertReferenceAndInvoiceLines(connection, analysisId);
            assertRejected(connection, c -> insertMatch(c, analysisId,
                    lines.referenceLineId(), lines.invoiceLineId(), "MATCHED", "AI",
                    new BigDecimal("1.01")));
        });
    }

    @Test
    void aReferenceLineCannotParticipateInMoreThanOneMatch() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            LinePair lines = insertReferenceAndInvoiceLines(connection, analysisId);
            insertMatch(connection, analysisId, lines.referenceLineId(), lines.invoiceLineId(),
                    "MATCHED", "MANUAL", null);
            UUID secondInvoiceLine = insertLineItem(connection, lines.invoiceExtractionId(), 1);

            assertRejected(connection, c -> insertMatch(c, analysisId,
                    lines.referenceLineId(), secondInvoiceLine, "MATCHED", "MANUAL", null));
        });
    }

    @Test
    void anInvoiceLineCannotParticipateInMoreThanOneMatch() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            LinePair lines = insertReferenceAndInvoiceLines(connection, analysisId);
            insertMatch(connection, analysisId, lines.referenceLineId(), lines.invoiceLineId(),
                    "MATCHED", "MANUAL", null);
            UUID secondReferenceLine = insertLineItem(connection, lines.referenceExtractionId(), 1);

            assertRejected(connection, c -> insertMatch(c, analysisId,
                    secondReferenceLine, lines.invoiceLineId(), "MATCHED", "MANUAL", null));
        });
    }

    @Test
    void aMatchCanHaveOnlyOneReconciliationSnapshot() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            LinePair lines = insertReferenceAndInvoiceLines(connection, analysisId);
            UUID matchId = insertMatch(connection, analysisId,
                    lines.referenceLineId(), lines.invoiceLineId(), "MATCHED", "MANUAL", null);
            insertReconciliationLine(connection, analysisId, matchId, "MATCHED");
            assertRejected(connection,
                    c -> insertReconciliationLine(c, analysisId, matchId, "DIFFERENT"));
        });
    }

    @Test
    void invalidReconciliationStatusIsRejected() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            LinePair lines = insertReferenceAndInvoiceLines(connection, analysisId);
            UUID matchId = insertMatch(connection, analysisId,
                    lines.referenceLineId(), lines.invoiceLineId(), "MATCHED", "MANUAL", null);
            assertRejected(connection,
                    c -> insertReconciliationLine(c, analysisId, matchId, "UNRESOLVED"));
        });
    }

    @Test
    void deletingAnalysisCascadesReconciliationAndEvidenceRows() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            LinePair lines = insertReferenceAndInvoiceLines(connection, analysisId);
            UUID matchId = insertMatch(connection, analysisId,
                    lines.referenceLineId(), lines.invoiceLineId(), "MATCHED", "MANUAL", null);
            UUID reconciliationLineId = insertReconciliationLine(
                    connection, analysisId, matchId, "DIFFERENT");
            UUID discrepancyId = insertDiscrepancy(connection, analysisId, reconciliationLineId,
                    "TOTAL_MISMATCH", "OPEN", null, null, false);
            insertEvidence(connection, discrepancyId, lines.referenceDocumentId(), 1);

            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM invoward.analyses WHERE id = ?")) {
                statement.setObject(1, analysisId);
                assertEquals(1, statement.executeUpdate());
            }

            assertEquals(0, countRows(connection, "line_item_matches"));
            assertEquals(0, countRows(connection, "reconciliation_lines"));
            assertEquals(0, countRows(connection, "discrepancies"));
            assertEquals(0, countRows(connection, "discrepancy_evidence"));
        });
    }
}
