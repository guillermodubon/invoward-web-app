package io.github.guillermodubon.invoward.document.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DocumentTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-30T12:00:00Z");

    @Test
    void uploadedDocumentStartsWithoutDetectedOrConfirmedType() {
        Document document = uploaded(DocumentRole.REFERENCE, null);

        assertNull(document.detectedType());
        assertNull(document.confirmedType());
        assertNull(document.expiresAt());
    }

    @Test
    void acceptsAnExpiryAfterCreationForGuestDocumentMetadata() {
        Instant expiresAt = CREATED_AT.plusSeconds(86_400);

        Document document = uploaded(DocumentRole.INVOICE, expiresAt);

        assertEquals(expiresAt, document.expiresAt());
    }

    @Test
    void rejectsMissingAndNonpositiveSizeOrPageCount() {
        assertThrows(NullPointerException.class, () -> copy(null, CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> copy(0, 1, CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> copy(-1, 1, CREATED_AT));
        assertThrows(NullPointerException.class, () -> copy(1, null, CREATED_AT));
        assertThrows(IllegalArgumentException.class, () -> copy(1, 0, CREATED_AT));
    }

    @Test
    void rejectsBlankOrOversizedMetadataAndInvalidSha256() {
        assertThrows(IllegalArgumentException.class, () -> copy(" ", "application/pdf", "key"));
        assertThrows(IllegalArgumentException.class,
                () -> copy("x".repeat(256), "application/pdf", "key"));
        assertThrows(IllegalArgumentException.class, () -> copy("invoice.pdf", " ", "key"));
        assertThrows(IllegalArgumentException.class,
                () -> copy("invoice.pdf", "a".repeat(101), "key"));
        assertThrows(IllegalArgumentException.class,
                () -> copy("invoice.pdf", "application/pdf", " "));
        assertThrows(IllegalArgumentException.class,
                () -> copy("invoice.pdf", "application/pdf", "k".repeat(513)));
        assertThrows(IllegalArgumentException.class,
                () -> copyWithHash("A".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> copyWithHash("g".repeat(64)));
    }

    @Test
    void confirmedTypeMustBeCompatibleWithDocumentRole() {
        assertThrows(IllegalArgumentException.class,
                () -> classified(DocumentRole.REFERENCE, DocumentType.INVOICE));
        assertThrows(IllegalArgumentException.class,
                () -> classified(DocumentRole.INVOICE, DocumentType.QUOTE));
        assertEquals(DocumentType.PURCHASE_ORDER,
                classified(DocumentRole.REFERENCE, DocumentType.PURCHASE_ORDER).confirmedType());
        assertEquals(DocumentType.UNKNOWN,
                classified(DocumentRole.INVOICE, DocumentType.UNKNOWN).confirmedType());
    }

    @Test
    void detectedTypeUpdateAcceptsEveryEnumSuggestionForEitherRole() {
        for (DocumentRole role : DocumentRole.values()) {
            for (DocumentType suggestion : DocumentType.values()) {
                assertEquals(suggestion, uploaded(role, null)
                        .withDetectedType(suggestion).detectedType());
            }
        }
    }

    @Test
    void confirmedTypeUpdateEnforcesRoleCompatibilityAndPreservesDetection() {
        Document reference = uploaded(DocumentRole.REFERENCE, null)
                .withDetectedType(DocumentType.INVOICE);
        Document invoice = uploaded(DocumentRole.INVOICE, null)
                .withDetectedType(DocumentType.PURCHASE_ORDER);

        assertEquals(DocumentType.ESTIMATE,
                reference.withConfirmedType(DocumentType.ESTIMATE).confirmedType());
        assertEquals(DocumentType.INVOICE,
                invoice.withConfirmedType(DocumentType.INVOICE).confirmedType());
        assertEquals(DocumentType.INVOICE,
                reference.withConfirmedType(DocumentType.QUOTE).detectedType());
        assertThrows(IllegalArgumentException.class,
                () -> reference.withConfirmedType(DocumentType.INVOICE));
        assertThrows(IllegalArgumentException.class,
                () -> invoice.withConfirmedType(DocumentType.QUOTE));
    }

    @Test
    void expiryMustBeAfterCreationAndStringRepresentationHidesStorageIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> uploaded(DocumentRole.REFERENCE, CREATED_AT));

        Document document = uploaded(DocumentRole.REFERENCE, null);
        assertFalse(document.toString().contains(document.sha256()));
        assertFalse(document.toString().contains(document.storageKey()));
    }

    private static Document uploaded(DocumentRole role, Instant expiresAt) {
        return Document.createUploaded(UUID.randomUUID(), UUID.randomUUID(), role,
                "quote.pdf", "application/pdf", 128, 2, "a".repeat(64),
                "documents/" + UUID.randomUUID() + ".pdf", expiresAt, CREATED_AT);
    }

    private static Document copy(Integer pageCount, Instant createdAt) {
        return new Document(UUID.randomUUID(), UUID.randomUUID(), DocumentRole.REFERENCE,
                null, null, "quote.pdf", "application/pdf", 128,
                pageCount, "a".repeat(64), "documents/key.pdf", null, createdAt);
    }

    private static Document copy(long sizeBytes, Integer pageCount, Instant createdAt) {
        return new Document(UUID.randomUUID(), UUID.randomUUID(), DocumentRole.REFERENCE,
                null, null, "quote.pdf", "application/pdf", sizeBytes,
                pageCount, "a".repeat(64), "documents/key.pdf", null, createdAt);
    }

    private static Document copy(String filename, String contentType, String storageKey) {
        return new Document(UUID.randomUUID(), UUID.randomUUID(), DocumentRole.REFERENCE,
                null, null, filename, contentType, 128, 1,
                "a".repeat(64), storageKey, null, CREATED_AT);
    }

    private static Document copyWithHash(String hash) {
        return new Document(UUID.randomUUID(), UUID.randomUUID(), DocumentRole.REFERENCE,
                null, null, "quote.pdf", "application/pdf", 128, 1,
                hash, "documents/key.pdf", null, CREATED_AT);
    }

    private static Document classified(DocumentRole role, DocumentType confirmedType) {
        return new Document(UUID.randomUUID(), UUID.randomUUID(), role,
                DocumentType.UNKNOWN, confirmedType, "quote.pdf", "application/pdf", 128, 1,
                "a".repeat(64), "documents/key.pdf", null, CREATED_AT);
    }
}
