package io.github.guillermodubon.invoward.persistence;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.countRows;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertDocument;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertExtractedDocument;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertLineItem;
import static io.github.guillermodubon.invoward.support.database.DatabaseFixtures.insertUserAnalysis;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentAndExtractionIntegrityIT extends PostgresIntegrityTestSupport {

    @Test
    void oneReferenceDocumentPerAnalysisIsAccepted() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            insertDocument(connection, analysisId, "REFERENCE", "QUOTE", "reference-key");
            assertEquals(1, countRows(connection, "documents"));
        });
    }

    @Test
    void oneInvoiceDocumentPerAnalysisIsAccepted() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            insertDocument(connection, analysisId, "INVOICE", "INVOICE", "invoice-key");
            assertEquals(1, countRows(connection, "documents"));
        });
    }

    @Test
    void aSecondReferenceDocumentPerAnalysisIsRejected() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            insertDocument(connection, analysisId, "REFERENCE", "QUOTE", "reference-one");
            assertRejected(connection, c -> insertDocument(
                    c, analysisId, "REFERENCE", "ESTIMATE", "reference-two"));
        });
    }

    @Test
    void aSecondInvoiceDocumentPerAnalysisIsRejected() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            insertDocument(connection, analysisId, "INVOICE", "INVOICE", "invoice-one");
            assertRejected(connection, c -> insertDocument(
                    c, analysisId, "INVOICE", "UNKNOWN", "invoice-two"));
        });
    }

    @Test
    void documentRoleAndConfirmedTypeMustMatchV1Vocabulary() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            assertRejected(connection, c -> insertDocument(
                    c, analysisId, "REPORT", null, "report-is-not-an-input-document"));
            assertRejected(connection, c -> insertDocument(
                    c, analysisId, "REFERENCE", "INVOICE", "bad-reference-type"));
            assertRejected(connection, c -> insertDocument(
                    c, analysisId, "INVOICE", "QUOTE", "bad-invoice-type"));
        });
    }

    @Test
    void documentSha256MustBeLowercaseHex() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            assertRejected(connection, c -> insertDocument(
                    c, analysisId, "REFERENCE", "QUOTE", "bad-hash", "g".repeat(64), 10, null));
        });
    }

    @Test
    void documentStorageKeyMustBeUnique() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            insertDocument(connection, analysisId, "REFERENCE", "QUOTE", "same-storage-key");
            assertRejected(connection, c -> insertDocument(
                    c, analysisId, "INVOICE", "INVOICE", "same-storage-key"));
        });
    }

    @Test
    void eachDocumentCanHaveAtMostOneExtractedDocument() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            UUID documentId = insertDocument(
                    connection, analysisId, "REFERENCE", "QUOTE", "extraction-source");
            insertExtractedDocument(connection, documentId, "DRAFT");
            assertRejected(connection,
                    c -> insertExtractedDocument(c, documentId, "DRAFT"));
        });
    }

    @Test
    void linePositionIsUniqueWithinAnExtractedDocument() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            UUID documentId = insertDocument(
                    connection, analysisId, "REFERENCE", "QUOTE", "line-source");
            UUID extractedDocumentId = insertExtractedDocument(connection, documentId, "DRAFT");
            insertLineItem(connection, extractedDocumentId, 0);
            assertRejected(connection,
                    c -> insertLineItem(c, extractedDocumentId, 0));
        });
    }

    @Test
    void boundingBoxMustBeAJsonObject() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            UUID documentId = insertDocument(
                    connection, analysisId, "REFERENCE", "QUOTE", "bounding-box-source");
            UUID extractedDocumentId = insertExtractedDocument(connection, documentId, "DRAFT");
            assertRejected(connection, c -> insertLineItem(
                    c, extractedDocumentId, 0, "Test item", null, null, null, null,
                    null, null, "[]"));
        });
    }

    @Test
    void extractionAndLineItemAmountsRejectForbiddenNegativeValues() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            UUID documentId = insertDocument(
                    connection, analysisId, "REFERENCE", "QUOTE", "negative-amount-source");
            UUID extractedDocumentId = insertExtractedDocument(connection, documentId, "DRAFT");

            for (String column : List.of("subtotal", "discount_total", "tax_total", "total")) {
                assertRejected(connection, c -> updateAmount(c,
                        "extracted_documents", column, extractedDocumentId));
            }

            UUID lineId = insertLineItem(connection, extractedDocumentId, 0);
            for (String column : List.of(
                    "quantity", "unit_price", "discount_amount", "tax_amount", "line_total")) {
                assertRejected(connection, c -> updateAmount(c,
                        "extracted_line_items", column, lineId));
            }
        });
    }

    @Test
    void confirmedExtractionRequiresConfirmationTimestamp() throws Exception {
        inTransaction(connection -> {
            UUID analysisId = insertUserAnalysis(connection);
            UUID documentId = insertDocument(
                    connection, analysisId, "REFERENCE", "QUOTE", "confirmation-source");
            assertRejected(connection, c -> insertExtractedDocument(
                    c, documentId, "CONFIRMED", null, null, null, null, false));
        });
    }

    private static void updateAmount(
            java.sql.Connection connection, String table, String column, UUID id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE invoward." + table + " SET " + column + " = ? WHERE id = ?")) {
            statement.setBigDecimal(1, new BigDecimal("-1"));
            statement.setObject(2, id);
            statement.executeUpdate();
        }
    }
}
