package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.ExtractionVersion;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionConfirmation;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDocumentPair;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionPreparation;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedLineItemRepository;
import io.github.guillermodubon.invoward.extraction.domain.BoundingBox;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "spring.ai.model.chat=none")
@Import(ConfirmExtractionServiceIT.FailureInjectionConfiguration.class)
class ConfirmExtractionServiceIT {

    @Autowired private ConfirmExtractionService confirmService;
    @Autowired private PersistExtractionTransaction persistTransaction;
    @Autowired private AnalysisRepository analysisRepository;
    @Autowired private AnalysisJobRepository analysisJobRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private ExtractedDocumentRepository extractedDocumentRepository;
    @Autowired private ExtractedLineItemRepository extractedLineItemRepository;
    @Autowired private FailureSwitch failureSwitch;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void resetFailureSwitch() {
        failureSwitch.reset();
    }

    @Test
    void confirmsBothDraftsAndPersistsTheSummaryAndMatchingWaitStateAtomically() {
        Fixture fixture = createFixture();

        confirmService.confirm(fixture.analysis().id(), fixture.owner(), confirmation(fixture));

        PersistedExtraction reference = extractedDocumentRepository
                .findByDocumentId(fixture.reference().id()).orElseThrow();
        PersistedExtraction invoice = extractedDocumentRepository
                .findByDocumentId(fixture.invoice().id()).orElseThrow();
        assertEquals(ExtractionStatus.CONFIRMED, reference.extraction().status());
        assertEquals(ExtractionStatus.CONFIRMED, invoice.extraction().status());
        assertNotNull(reference.extraction().confirmedAt());
        assertEquals(reference.extraction().confirmedAt(), invoice.extraction().confirmedAt());
        assertEquals(1, reference.version());
        assertEquals(1, invoice.version());
        assertEquals(fixture.draft().reference().lineItemIds(), reference.lineItemIds());
        assertEquals(fixture.draft().invoice().lineItemIds(), invoice.lineItemIds());
        assertEquals(fixture.draft().reference().lineItemIds(), extractedLineItemRepository
                .findByExtractedDocumentId(reference.id()).stream().map(line -> line.id()).toList());

        Analysis matching = analysisRepository.findOwnedById(
                fixture.analysis().id(), fixture.owner(), clock.instant()).orElseThrow();
        assertEquals(AnalysisStatus.MATCHING, matching.status());
        assertEquals("PURCHASE_ORDER", matching.referenceType());
        assertEquals("PO-42", matching.referenceNumber());
        assertEquals("INV-42", matching.invoiceNumber());
        assertEquals("ACME\u00a0  INDUSTRIAL", matching.supplierName());
        assertEquals("acme industrial", matching.supplierKey());
        assertEquals("EUR", matching.currency());
        assertEquals(new BigDecimal("100.0000"), matching.referenceTotal());
        assertEquals(new BigDecimal("132.5000"), matching.invoicedTotal());
        assertNull(matching.difference());
        assertNull(matching.reconciliationStatus());

        AnalysisJob job = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, job.status());
        assertEquals(AnalysisStatus.MATCHING, job.currentStage());
        assertEquals(0, job.attemptCount());
        assertNull(job.startedAt());
        assertNull(job.completedAt());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.line_item_matches WHERE analysis_id = ?",
                Integer.class, fixture.analysis().id()));
    }

    @Test
    void rollsBackFirstConfirmationWhenTheSecondOptimisticWriteConflicts() {
        Fixture fixture = createFixture();
        failureSwitch.failOnConfirm(2);

        assertThrows(ExtractionConflictException.class, () -> confirmService.confirm(
                fixture.analysis().id(), fixture.owner(), confirmation(fixture)));

        PersistedExtraction reference = extractedDocumentRepository
                .findByDocumentId(fixture.reference().id()).orElseThrow();
        PersistedExtraction invoice = extractedDocumentRepository
                .findByDocumentId(fixture.invoice().id()).orElseThrow();
        assertEquals(ExtractionStatus.DRAFT, reference.extraction().status());
        assertNull(reference.extraction().confirmedAt());
        assertEquals(0, reference.version());
        assertEquals(ExtractionStatus.DRAFT, invoice.extraction().status());
        assertNull(invoice.extraction().confirmedAt());
        assertEquals(0, invoice.version());
        assertEquals(fixture.draft().reference().lineItemIds(), reference.lineItemIds());

        Analysis unchanged = analysisRepository.findOwnedById(
                fixture.analysis().id(), fixture.owner(), clock.instant()).orElseThrow();
        assertEquals(AnalysisStatus.AWAITING_CONFIRMATION, unchanged.status());
        assertNull(unchanged.referenceTotal());
        AnalysisJob job = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, job.status());
        assertEquals(AnalysisStatus.AWAITING_CONFIRMATION, job.currentStage());
    }

    private Fixture createFixture() {
        Instant now = clock.instant();
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        AnalysisOwner owner = new RegisteredUserOwner(userId);
        Analysis analysis = Analysis.create(UUID.randomUUID(), owner, PriceTolerance.exactMatch(), now)
                .transitionToUploading(now)
                .markClassifying(now);
        analysisRepository.create(analysis);
        analysisJobRepository.create(AnalysisJob.waitingForUser(UUID.randomUUID(), analysis.id(), now)
                .awaitMoreUploads(now)
                .waitForUserAtClassification(now));

        String prefix = "confirm-extraction-test/" + UUID.randomUUID();
        Document reference = document(analysis.id(), DocumentRole.REFERENCE,
                DocumentType.PURCHASE_ORDER, prefix + "/reference", now);
        Document invoice = document(analysis.id(), DocumentRole.INVOICE,
                DocumentType.INVOICE, prefix + "/invoice", now);
        documentRepository.create(reference);
        documentRepository.create(invoice);
        ExtractionPreparation.Ready ready = new ExtractionPreparation.Ready(
                new ExtractionDocumentPair(reference, invoice),
                draft(ExtractionSource.AI, "Reference Supplier", "PO-42", "USD", "100.0000"),
                draft(ExtractionSource.CACHE, "ACME\u00a0  INDUSTRIAL", "INV-42", "EUR", "132.5000"),
                "test-model");
        ExtractionReview saved = persistTransaction.persist(analysis.id(), owner, ready);
        return new Fixture(analysis, owner, reference, invoice, saved);
    }

    private static Document document(
            UUID analysisId, DocumentRole role, DocumentType type, String storageKey, Instant now) {
        return Document.createUploaded(UUID.randomUUID(), analysisId, role, role + ".pdf",
                "application/pdf", 128, 1, DatabaseFixtures.sha256(), storageKey, null, now)
                .withDetectedType(type)
                .withConfirmedType(type);
    }

    private static ExtractionConfirmation confirmation(Fixture fixture) {
        return new ExtractionConfirmation(fixture.reference().id(), fixture.draft().reference().version(),
                fixture.invoice().id(), fixture.draft().invoice().version());
    }

    private static ExtractedDocument draft(
            ExtractionSource source, String vendor, String number, String currency, String total) {
        BigDecimal amount = new BigDecimal(total);
        return ExtractedDocument.draft(source, vendor, number, LocalDate.parse("2026-10-01"), currency,
                amount, BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(4), amount,
                List.of(line("Part " + number)));
    }

    private static ExtractedLineItem line(String description) {
        return new ExtractedLineItem(0, "SKU-42", description, BigDecimal.ONE, "each",
                new BigDecimal("10.0000"), BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(4),
                new BigDecimal("10.0000"), 1, "Printed: " + description,
                new BoundingBox(0.1, 0.2, 0.8, 0.9));
    }

    private record Fixture(
            Analysis analysis,
            AnalysisOwner owner,
            Document reference,
            Document invoice,
            ExtractionReview draft) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailureInjectionConfiguration {

        @Bean
        FailureSwitch failureSwitch() {
            return new FailureSwitch();
        }

        @Bean
        @Primary
        ExtractedDocumentRepository failureInjectingExtractionRepository(
                @Qualifier("jpaExtractedDocumentRepository") ExtractedDocumentRepository delegate,
                FailureSwitch failureSwitch) {
            return new FailureInjectingRepository(delegate, failureSwitch);
        }
    }

    static final class FailureSwitch {
        private int failOnConfirm = -1;
        private int confirmCount;

        synchronized void failOnConfirm(int ordinal) {
            failOnConfirm = ordinal;
            confirmCount = 0;
        }

        synchronized boolean shouldFailConfirm() {
            if (failOnConfirm < 0) {
                return false;
            }
            confirmCount++;
            if (confirmCount == failOnConfirm) {
                failOnConfirm = -1;
                return true;
            }
            return false;
        }

        synchronized void reset() {
            failOnConfirm = -1;
            confirmCount = 0;
        }
    }

    private static final class FailureInjectingRepository implements ExtractedDocumentRepository {
        private final ExtractedDocumentRepository delegate;
        private final FailureSwitch failureSwitch;

        private FailureInjectingRepository(
                ExtractedDocumentRepository delegate,
                FailureSwitch failureSwitch) {
            this.delegate = delegate;
            this.failureSwitch = failureSwitch;
        }

        @Override
        public PersistedExtraction create(
                UUID documentId, ExtractedDocument extraction, String extractorVersion,
                String modelId, int schemaVersion, Instant now) {
            return delegate.create(documentId, extraction, extractorVersion, modelId, schemaVersion, now);
        }

        @Override
        public Optional<PersistedExtraction> findByDocumentId(UUID documentId) {
            return delegate.findByDocumentId(documentId);
        }

        @Override
        public Optional<PersistedExtraction> findByIdAndDocumentId(UUID extractionId, UUID documentId) {
            return delegate.findByIdAndDocumentId(extractionId, documentId);
        }

        @Override
        public Optional<PersistedExtraction> update(PersistedExtraction extraction, Instant now) {
            return delegate.update(extraction, now);
        }

        @Override
        public Optional<PersistedExtraction> confirm(PersistedExtraction extraction, Instant confirmedAt) {
            return failureSwitch.shouldFailConfirm()
                    ? Optional.empty()
                    : delegate.confirm(extraction, confirmedAt);
        }
    }
}
