package io.github.guillermodubon.invoward.document.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.application.exception.DocumentRoleAlreadyExistsException;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "invoward.email.provider=disabled")
@Transactional
class DocumentPersistenceIT {

    private static final Instant CREATED_AT = Instant.parse("2026-09-30T12:00:00Z");

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void persistsRegisteredDocumentMetadataWithUnclassifiedTypesAndNullExpiry() {
        UUID analysisId = insertRegisteredAnalysis();
        Document document = newDocument(analysisId, DocumentRole.REFERENCE, 125L, null);

        Document persisted = documentRepository.create(document);

        assertEquals(document, persisted);
        assertNull(persisted.detectedType());
        assertNull(persisted.confirmedType());
        assertNull(persisted.expiresAt());
        assertEquals(document, documentRepository.findByIdAndAnalysisId(
                document.id(), analysisId).orElseThrow());
        assertEquals(1, documentRepository.findByAnalysisId(analysisId).size());
        assertTrue(documentRepository.existsByAnalysisIdAndRole(analysisId, DocumentRole.REFERENCE));
        assertFalse(documentRepository.existsByAnalysisIdAndRole(analysisId, DocumentRole.INVOICE));
        assertEquals(125L, documentRepository.sumSizeBytesByAnalysisId(analysisId));
    }

    @Test
    void persistsTypeUpdatesAndAcceptsRoleIncompatibleDetectionSuggestions() {
        UUID analysisId = insertRegisteredAnalysis();
        Document reference = newDocument(analysisId, DocumentRole.REFERENCE, 125L, null);
        Document invoice = newDocument(analysisId, DocumentRole.INVOICE, 175L, null);
        documentRepository.create(reference);
        documentRepository.create(invoice);

        for (DocumentType suggestion : DocumentType.values()) {
            reference = documentRepository.updateTypes(reference.withDetectedType(suggestion))
                    .orElseThrow();
            invoice = documentRepository.updateTypes(invoice.withDetectedType(suggestion))
                    .orElseThrow();
            assertEquals(suggestion, reference.detectedType());
            assertEquals(suggestion, invoice.detectedType());
        }

        reference = documentRepository.updateTypes(
                reference.withConfirmedType(DocumentType.PURCHASE_ORDER)).orElseThrow();
        invoice = documentRepository.updateTypes(
                invoice.withConfirmedType(DocumentType.INVOICE)).orElseThrow();

        assertEquals(reference, documentRepository.findByIdAndAnalysisId(
                reference.id(), analysisId).orElseThrow());
        assertEquals(invoice, documentRepository.findByIdAndAnalysisId(
                invoice.id(), analysisId).orElseThrow());
        assertEquals("reference.pdf", reference.originalFilename());
        assertEquals("invoice.pdf", invoice.originalFilename());
    }

    @Test
    void permitsOneReferenceAndOneInvoiceForTheSameAnalysis() {
        UUID analysisId = insertRegisteredAnalysis();
        Document reference = newDocument(analysisId, DocumentRole.REFERENCE, 125L, null);
        Document invoice = newDocument(analysisId, DocumentRole.INVOICE, 175L, null);

        documentRepository.create(reference);
        documentRepository.create(invoice);

        assertEquals(2, documentRepository.findByAnalysisId(analysisId).size());
        assertEquals(300L, documentRepository.sumSizeBytesByAnalysisId(analysisId));
    }

    @Test
    void repositoryMapsDatabaseRejectionOfASecondReferenceToSafeRoleConflict() {
        UUID analysisId = insertRegisteredAnalysis();
        documentRepository.create(newDocument(analysisId, DocumentRole.REFERENCE, 100L, null));

        assertThrows(DocumentRoleAlreadyExistsException.class,
                () -> documentRepository.create(
                        newDocument(analysisId, DocumentRole.REFERENCE, 200L, null)));
    }

    @Test
    void repositoryMapsDatabaseRejectionOfASecondInvoiceToSafeRoleConflict() {
        UUID analysisId = insertRegisteredAnalysis();
        documentRepository.create(newDocument(analysisId, DocumentRole.INVOICE, 100L, null));

        assertThrows(DocumentRoleAlreadyExistsException.class,
                () -> documentRepository.create(
                        newDocument(analysisId, DocumentRole.INVOICE, 200L, null)));
    }

    @Test
    void lookupIsScopedToTheSuppliedAnalysisId() {
        UUID owningAnalysisId = insertRegisteredAnalysis();
        UUID otherAnalysisId = insertRegisteredAnalysis();
        Document document = newDocument(owningAnalysisId, DocumentRole.REFERENCE, 125L, null);
        documentRepository.create(document);

        assertTrue(documentRepository.findByIdAndAnalysisId(
                document.id(), owningAnalysisId).isPresent());
        assertFalse(documentRepository.findByIdAndAnalysisId(
                document.id(), otherAnalysisId).isPresent());
        assertTrue(documentRepository.findByAnalysisId(otherAnalysisId).isEmpty());
    }

    @Test
    void sumsOnlyActualDocumentBytesForTheRequestedAnalysisAndReturnsZeroWhenEmpty() {
        UUID analysisId = insertRegisteredAnalysis();
        UUID otherAnalysisId = insertRegisteredAnalysis();
        documentRepository.create(newDocument(analysisId, DocumentRole.REFERENCE, 10L, null));
        documentRepository.create(newDocument(analysisId, DocumentRole.INVOICE, 25L, null));
        documentRepository.create(newDocument(otherAnalysisId, DocumentRole.REFERENCE, 90L, null));

        assertEquals(35L, documentRepository.sumSizeBytesByAnalysisId(analysisId));
        assertEquals(90L, documentRepository.sumSizeBytesByAnalysisId(otherAnalysisId));
        assertEquals(0L, documentRepository.sumSizeBytesByAnalysisId(UUID.randomUUID()));
    }

    @Test
    void preservesGuestAnalysisExpiryOnTheDocument() {
        UUID analysisId = insertGuestAnalysis();
        Instant analysisExpiry = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM invoward.analyses WHERE id = ?",
                (result, row) -> result.getTimestamp(1).toInstant(), analysisId);
        Document document = newDocument(
                analysisId, DocumentRole.REFERENCE, 125L, analysisExpiry);

        Document persisted = documentRepository.create(document);

        assertEquals(analysisExpiry, persisted.expiresAt());
        assertEquals(analysisExpiry, documentRepository.findByIdAndAnalysisId(
                document.id(), analysisId).orElseThrow().expiresAt());
    }

    @Test
    void databaseRejectsDuplicateStorageKeys() {
        UUID analysisId = insertRegisteredAnalysis();
        String storageKey = "test/" + UUID.randomUUID();
        documentRepository.create(newDocument(
                UUID.randomUUID(), analysisId, DocumentRole.REFERENCE, 100L, null, storageKey));

        assertThrows(DataIntegrityViolationException.class,
                () -> documentRepository.create(newDocument(
                        UUID.randomUUID(), analysisId, DocumentRole.INVOICE, 100L, null, storageKey)));
    }

    private UUID insertRegisteredAnalysis() {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUserAnalysis);
    }

    private UUID insertGuestAnalysis() {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertGuestAnalysis);
    }

    private static Document newDocument(
            UUID analysisId, DocumentRole role, long sizeBytes, Instant expiresAt) {
        return newDocument(UUID.randomUUID(), analysisId, role, sizeBytes, expiresAt,
                "test/" + UUID.randomUUID());
    }

    private static Document newDocument(
            UUID id,
            UUID analysisId,
            DocumentRole role,
            long sizeBytes,
            Instant expiresAt,
            String storageKey) {
        Instant createdAt = expiresAt == null ? CREATED_AT : expiresAt.minusSeconds(3_600);
        return Document.createUploaded(id, analysisId, role,
                role == DocumentRole.REFERENCE ? "reference.pdf" : "invoice.pdf",
                "application/pdf", sizeBytes, 1, "a".repeat(64), storageKey,
                expiresAt, createdAt);
    }
}
