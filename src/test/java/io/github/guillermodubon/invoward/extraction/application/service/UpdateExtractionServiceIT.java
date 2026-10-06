package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
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
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDocumentPair;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionPreparation;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReviewUpdate;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "spring.ai.model.chat=none")
@Import(UpdateExtractionServiceIT.FailureInjectionConfiguration.class)
class UpdateExtractionServiceIT {

    @Autowired private UpdateExtractionService updateService;
    @Autowired private PersistExtractionTransaction persistTransaction;
    @Autowired private AnalysisRepository analysisRepository;
    @Autowired private AnalysisJobRepository analysisJobRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private ExtractedDocumentRepository extractedDocumentRepository;
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
    void persistsHumanReviewReplacementAndEvidenceRulesInPostgres() {
        Fixture fixture = createFixture();
        ExtractionReview initial = persistTransaction.persist(
                fixture.analysis().id(), fixture.owner(), preparation(fixture));
        ExtractionReviewUpdate request = updateRequest(fixture, initial);

        ExtractionReview saved = updateService.update(fixture.analysis().id(), fixture.owner(), request);

        assertEquals(1, saved.reference().version());
        assertEquals(DocumentType.ESTIMATE, saved.reference().confirmedType());
        assertEquals("Corrected supplier", saved.reference().extraction().vendorName());
        assertEquals(3, saved.reference().extraction().lines().size());
        assertEquals("Washers", saved.reference().extraction().lines().get(0).description());
        assertEquals(1, saved.reference().extraction().lines().get(0).pageNumber());
        assertNull(saved.reference().extraction().lines().get(1).pageNumber());
        assertNull(saved.reference().extraction().lines().get(1).sourceText());
        assertNull(saved.reference().extraction().lines().get(1).boundingBox());
        assertNull(saved.reference().extraction().lines().get(2).sourceText());
        assertEquals("manual part", saved.reference().extraction().lines().get(2).normalizedDescription());
        assertEquals(1, saved.invoice().version());

        PersistedExtraction persistedReference = extractedDocumentRepository
                .findByDocumentId(fixture.reference().id()).orElseThrow();
        assertEquals(ExtractionStatus.DRAFT, persistedReference.extraction().status());
        assertEquals(3, persistedReference.extraction().lines().size());
        assertEquals(DocumentType.ESTIMATE, documentRepository
                .findByIdAndAnalysisId(fixture.reference().id(), fixture.analysis().id()).orElseThrow()
                .confirmedType());
        assertEquals(AnalysisStatus.AWAITING_CONFIRMATION, analysisRepository
                .findOwnedById(fixture.analysis().id(), fixture.owner(), clock.instant()).orElseThrow().status());
    }

    @Test
    void rollsBackBothDocumentsWhenSecondOptimisticWriteConflicts() {
        Fixture fixture = createFixture();
        ExtractionReview initial = persistTransaction.persist(
                fixture.analysis().id(), fixture.owner(), preparation(fixture));
        failureSwitch.failOnUpdate(2);

        assertThrows(ExtractionConflictException.class, () -> updateService.update(
                fixture.analysis().id(), fixture.owner(), updateRequest(fixture, initial)));

        PersistedExtraction reference = extractedDocumentRepository
                .findByDocumentId(fixture.reference().id()).orElseThrow();
        PersistedExtraction invoice = extractedDocumentRepository
                .findByDocumentId(fixture.invoice().id()).orElseThrow();
        assertEquals(0, reference.version());
        assertEquals("InvoWard Test Supplier", reference.extraction().vendorName());
        assertEquals(0, invoice.version());
        assertEquals(DocumentType.PURCHASE_ORDER, documentRepository
                .findByIdAndAnalysisId(fixture.reference().id(), fixture.analysis().id()).orElseThrow()
                .confirmedType());
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.extracted_line_items li "
                        + "JOIN invoward.extracted_documents ed ON ed.id = li.extracted_document_id "
                        + "JOIN invoward.documents d ON d.id = ed.document_id WHERE d.analysis_id = ?",
                Integer.class, fixture.analysis().id()));
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

        String keyPrefix = "update-extraction-test/" + UUID.randomUUID();
        Document reference = documentRepository.create(Document.createUploaded(
                UUID.randomUUID(), analysis.id(), DocumentRole.REFERENCE, "reference.pdf",
                "application/pdf", 128, 2, DatabaseFixtures.sha256(), keyPrefix + "/reference", null, now)
                .withDetectedType(DocumentType.QUOTE)
                .withConfirmedType(DocumentType.PURCHASE_ORDER));
        Document invoice = documentRepository.create(Document.createUploaded(
                UUID.randomUUID(), analysis.id(), DocumentRole.INVOICE, "invoice.pdf",
                "application/pdf", 128, 1, DatabaseFixtures.sha256(), keyPrefix + "/invoice", null, now)
                .withDetectedType(DocumentType.INVOICE)
                .withConfirmedType(DocumentType.INVOICE));
        return new Fixture(analysis, owner, reference, invoice);
    }

    private ExtractionReviewUpdate updateRequest(Fixture fixture, ExtractionReview initial) {
        ExtractionReviewUpdate.DocumentUpdate referenceUpdate = new ExtractionReviewUpdate.DocumentUpdate(
                fixture.reference().id(), initial.reference().version(), DocumentType.ESTIMATE,
                "Corrected supplier", "DOC-100", LocalDate.parse("2026-10-01"), "USD",
                new BigDecimal("100.0000"), BigDecimal.ZERO.setScale(4), new BigDecimal("5.0000"),
                new BigDecimal("105.0000"), List.of(
                        lineUpdate(initial.reference().lineItemIds().get(1), "ITEM-1", "Washers"),
                        lineUpdate(initial.reference().lineItemIds().get(0), "ITEM-0", "Corrected fasteners"),
                        lineUpdate(null, "MANUAL-1", "Manual part")));
        ExtractionReviewUpdate.DocumentUpdate invoiceUpdate = new ExtractionReviewUpdate.DocumentUpdate(
                fixture.invoice().id(), initial.invoice().version(), DocumentType.INVOICE,
                "InvoWard Test Supplier", "DOC-100", LocalDate.parse("2026-10-01"), "USD",
                new BigDecimal("100.0000"), BigDecimal.ZERO.setScale(4), new BigDecimal("5.0000"),
                new BigDecimal("105.0000"), List.of(lineUpdate(
                        initial.invoice().lineItemIds().getFirst(), "ITEM-0", "Delivery")));
        return new ExtractionReviewUpdate(List.of(invoiceUpdate, referenceUpdate));
    }

    private static ExtractionPreparation.Ready preparation(Fixture fixture) {
        return new ExtractionPreparation.Ready(
                new ExtractionDocumentPair(fixture.reference(), fixture.invoice()),
                draft(ExtractionSource.AI, List.of(line(0, "Steel fasteners"), line(1, "Washers"))),
                draft(ExtractionSource.CACHE, List.of(line(0, "Delivery"))),
                "test-model");
    }

    private static ExtractedDocument draft(ExtractionSource source, List<ExtractedLineItem> lines) {
        return ExtractedDocument.draft(source, "InvoWard Test Supplier", "DOC-100",
                LocalDate.parse("2026-10-01"), "USD", new BigDecimal("100.0000"),
                BigDecimal.ZERO.setScale(4), new BigDecimal("5.0000"), new BigDecimal("105.0000"), lines);
    }

    private static ExtractedLineItem line(int position, String description) {
        return new ExtractedLineItem(position, "ITEM-" + position, description, new BigDecimal("2.0000"),
                "each", new BigDecimal("10.0000"), BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(4),
                new BigDecimal("20.0000"), 1, "Printed: " + description,
                new BoundingBox(0.1, 0.2, 0.7, 0.8));
    }

    private static ExtractionReviewUpdate.LineUpdate lineUpdate(UUID id, String itemCode, String description) {
        return new ExtractionReviewUpdate.LineUpdate(id, itemCode, description, new BigDecimal("2.0000"), "each",
                new BigDecimal("10.0000"), BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(4),
                new BigDecimal("20.0000"));
    }

    private record Fixture(Analysis analysis, AnalysisOwner owner, Document reference, Document invoice) {
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
        private int failOnUpdate = -1;
        private int updateCount;

        synchronized void failOnUpdate(int ordinal) {
            failOnUpdate = ordinal;
            updateCount = 0;
        }

        synchronized boolean shouldFailUpdate() {
            if (failOnUpdate < 0) {
                return false;
            }
            updateCount++;
            if (updateCount == failOnUpdate) {
                failOnUpdate = -1;
                return true;
            }
            return false;
        }

        synchronized void reset() {
            failOnUpdate = -1;
            updateCount = 0;
        }
    }

    private static final class FailureInjectingRepository implements ExtractedDocumentRepository {
        private final ExtractedDocumentRepository delegate;
        private final FailureSwitch failureSwitch;

        private FailureInjectingRepository(ExtractedDocumentRepository delegate, FailureSwitch failureSwitch) {
            this.delegate = delegate;
            this.failureSwitch = failureSwitch;
        }

        @Override
        public PersistedExtraction create(UUID documentId, ExtractedDocument extraction, String extractorVersion,
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
            return failureSwitch.shouldFailUpdate()
                    ? Optional.empty()
                    : delegate.update(extraction, now);
        }

        @Override
        public Optional<PersistedExtraction> confirm(PersistedExtraction extraction, Instant confirmedAt) {
            return delegate.confirm(extraction, confirmedAt);
        }
    }
}
