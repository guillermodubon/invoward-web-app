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
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDocumentPair;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionPreparation;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "spring.ai.model.chat=none")
@Import(PersistExtractionTransactionIT.FailureInjectionConfiguration.class)
class PersistExtractionTransactionIT {

    @Autowired private PersistExtractionTransaction transaction;
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
    void persistsBothDraftsAndLinesWithExactAnalysisAndJobTransitions() {
        Fixture fixture = createFixture();

        ExtractionReview review = transaction.persist(
                fixture.analysis().id(), fixture.owner(), preparation(fixture));

        assertEquals(fixture.reference().id(), review.reference().documentId());
        assertEquals(fixture.invoice().id(), review.invoice().documentId());
        assertEquals(ExtractionStatus.DRAFT, review.reference().extraction().status());
        assertEquals(ExtractionStatus.DRAFT, review.invoice().extraction().status());
        assertNull(review.reference().extraction().confirmedAt());
        assertNull(review.invoice().extraction().confirmedAt());
        assertEquals(0, review.reference().version());
        assertEquals(0, review.invoice().version());
        assertEquals(ExtractionSource.AI, review.reference().extraction().extractionSource());
        assertEquals(ExtractionSource.CACHE, review.invoice().extraction().extractionSource());

        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.extracted_documents ed "
                        + "JOIN invoward.documents d ON d.id = ed.document_id WHERE d.analysis_id = ?",
                Integer.class, fixture.analysis().id()));
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.extracted_line_items li "
                        + "JOIN invoward.extracted_documents ed ON ed.id = li.extracted_document_id "
                        + "JOIN invoward.documents d ON d.id = ed.document_id WHERE d.analysis_id = ?",
                Integer.class, fixture.analysis().id()));
        assertEquals(2, extractedLineItemRepository.findByExtractedDocumentId(
                review.reference().extractedDocumentId()).size());
        assertEquals(1, extractedLineItemRepository.findByExtractedDocumentId(
                review.invoice().extractedDocumentId()).size());
        assertTrue(extractedDocumentRepository.findByDocumentId(fixture.reference().id()).isPresent());
        assertTrue(extractedDocumentRepository.findByDocumentId(fixture.invoice().id()).isPresent());

        Analysis analysis = analysisRepository.findOwnedById(
                fixture.analysis().id(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob job = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
        assertEquals(AnalysisStatus.AWAITING_CONFIRMATION, analysis.status());
        assertEquals(AnalysisStatus.CLASSIFYING, fixture.analysis().status());
        assertNull(analysis.difference());
        assertFalse(analysis.retryable());
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, job.status());
        assertEquals(AnalysisStatus.AWAITING_CONFIRMATION, job.currentStage());
        assertEquals(0, job.attemptCount());
        assertFalse(job.retryable());
        assertNull(job.lastErrorCode());
        assertNull(job.lastErrorMessage());
        assertNull(job.startedAt());
        assertNull(job.completedAt());
    }

    @Test
    void rollsBackFirstDraftLinesAndWorkflowChangesWhenSecondDraftPersistenceFails() {
        Fixture fixture = createFixture();
        failureSwitch.failOnCreate(2);

        assertThrows(InjectedPersistenceFailure.class, () -> transaction.persist(
                fixture.analysis().id(), fixture.owner(), preparation(fixture)));

        assertTrue(extractedDocumentRepository.findByDocumentId(fixture.reference().id()).isEmpty());
        assertTrue(extractedDocumentRepository.findByDocumentId(fixture.invoice().id()).isEmpty());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.extracted_documents ed "
                        + "JOIN invoward.documents d ON d.id = ed.document_id WHERE d.analysis_id = ?",
                Integer.class, fixture.analysis().id()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.extracted_line_items li "
                        + "JOIN invoward.extracted_documents ed ON ed.id = li.extracted_document_id "
                        + "JOIN invoward.documents d ON d.id = ed.document_id WHERE d.analysis_id = ?",
                Integer.class, fixture.analysis().id()));
        assertEquals(AnalysisStatus.CLASSIFYING, analysisRepository.findOwnedById(
                fixture.analysis().id(), fixture.owner(), clock.instant()).orElseThrow().status());
        AnalysisJob job = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, job.status());
        assertEquals(AnalysisStatus.CLASSIFYING, job.currentStage());
    }

    @Test
    void rejectsAnExistingPerDocumentExtractionWithoutCreatingTheOtherDraftOrAdvancingState() {
        Fixture fixture = createFixture();
        extractedDocumentRepository.create(
                fixture.reference().id(), draft(ExtractionSource.AI, List.of()),
                ExtractionVersion.EXTRACTOR_VERSION, "test-model", ExtractionVersion.SCHEMA_VERSION,
                clock.instant());

        assertThrows(ExtractionConflictException.class, () -> transaction.persist(
                fixture.analysis().id(), fixture.owner(), preparation(fixture)));

        assertTrue(extractedDocumentRepository.findByDocumentId(fixture.reference().id()).isPresent());
        assertTrue(extractedDocumentRepository.findByDocumentId(fixture.invoice().id()).isEmpty());
        assertEquals(AnalysisStatus.CLASSIFYING, analysisRepository.findOwnedById(
                fixture.analysis().id(), fixture.owner(), clock.instant()).orElseThrow().status());
        assertEquals(AnalysisStatus.CLASSIFYING, analysisJobRepository.findByAnalysisId(
                fixture.analysis().id()).orElseThrow().currentStage());
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

        String keyPrefix = "persist-extraction-test/" + UUID.randomUUID();
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
        private int failOnCreate = -1;
        private int createCount;

        synchronized void failOnCreate(int ordinal) {
            failOnCreate = ordinal;
            createCount = 0;
        }

        synchronized void beforeCreate() {
            if (failOnCreate < 0) {
                return;
            }
            createCount++;
            if (createCount == failOnCreate) {
                failOnCreate = -1;
                throw new InjectedPersistenceFailure();
            }
        }

        synchronized void reset() {
            failOnCreate = -1;
            createCount = 0;
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
        public io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction create(
                UUID documentId,
                ExtractedDocument extraction,
                String extractorVersion,
                String modelId,
                int schemaVersion,
                Instant now) {
            failureSwitch.beforeCreate();
            return delegate.create(documentId, extraction, extractorVersion, modelId, schemaVersion, now);
        }

        @Override
        public Optional<io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction>
                findByDocumentId(UUID documentId) {
            return delegate.findByDocumentId(documentId);
        }

        @Override
        public Optional<io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction>
                findByIdAndDocumentId(UUID extractionId, UUID documentId) {
            return delegate.findByIdAndDocumentId(extractionId, documentId);
        }

        @Override
        public Optional<io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction> update(
                io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction extraction,
                Instant now) {
            return delegate.update(extraction, now);
        }

        @Override
        public Optional<io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction> confirm(
                io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction extraction,
                Instant confirmedAt) {
            return delegate.confirm(extraction, confirmedAt);
        }
    }

    private static final class InjectedPersistenceFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
