package io.github.guillermodubon.invoward.persistence;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertDiscrepancy;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertDocument;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertEvidence;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertUserAnalysis;

class DiscrepancyIntegrityIT extends PostgresIntegrityTestSupport {

    @Test
    void discrepancyTypeMustBeFromTheV1Vocabulary() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            assertRejected(connection, c -> insertDiscrepancy(c, analysisId, null,
                    "PRICE_CHANGED", "OPEN", null, null, false));
        });
    }

    @Test
    void openDiscrepancyCannotHaveResolvedTimestamp() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            assertRejected(connection, c -> insertDiscrepancy(c, analysisId, null,
                    "TOTAL_MISMATCH", "OPEN", null, null, true));
        });
    }

    @Test
    void acceptedAndResolvedDiscrepanciesRequireReasonAndTimestamp() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            for (String status : new String[]{"ACCEPTED", "RESOLVED"}) {
                assertRejected(connection, c -> insertDiscrepancy(c, analysisId, null,
                        "TOTAL_MISMATCH", status, null, null, true));
                assertRejected(connection, c -> insertDiscrepancy(c, analysisId, null,
                        "TOTAL_MISMATCH", status, "APPROVED_CHANGE", null, false));
            }
        });
    }

    @Test
    void otherResolutionReasonRequiresANonblankNote() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            assertRejected(connection, c -> insertDiscrepancy(c, analysisId, null,
                    "TOTAL_MISMATCH", "RESOLVED", "OTHER", "   ", true));
        });
    }

    @Test
    void discrepancyEvidencePageNumberMustBePositive() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            UUID documentId = insertDocument(
                    connection, analysisId, "REFERENCE", "QUOTE", "evidence-document");
            UUID discrepancyId = insertDiscrepancy(connection, analysisId, null,
                    "TOTAL_MISMATCH", "OPEN", null, null, false);
            assertRejected(connection,
                    c -> insertEvidence(c, discrepancyId, documentId, 0));
        });
    }
}
