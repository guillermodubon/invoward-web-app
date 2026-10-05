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
import io.github.guillermodubon.invoward.document.application.model.StorageObjectUpload;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException.Failure;
import io.github.guillermodubon.invoward.extraction.application.model.DetectedDocumentType;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import io.github.guillermodubon.invoward.support.storage.FakeDocumentStorage;
import io.github.guillermodubon.invoward.support.ai.FakeDocumentIntelligence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "spring.ai.model.chat=none")
@Import(DetectDocumentTypesServiceIT.TestProviderConfiguration.class)
class DetectDocumentTypesServiceIT {

    private static final byte[] CONTENT = "synthetic test PDF bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Autowired private DetectDocumentTypesService service;
    @Autowired private AnalysisRepository analysisRepository;
    @Autowired private AnalysisJobRepository analysisJobRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;
    @Autowired private FakeDocumentStorage storage;
    @Autowired private FakeDocumentIntelligence intelligence;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void clearTestDoubles() {
        storage.clear();
        intelligence.reset();
    }

    @Test
    void classifiesTwoMissingDocumentsPersistsSuggestionsAndTransitionsAtomicallyThenIsIdempotent()
            throws IOException {
        Fixture fixture = createAnalysisWithDocuments();
        intelligence.setClassification(fixture.reference().id(), DocumentType.QUOTE);
        intelligence.setClassification(fixture.invoice().id(), DocumentType.INVOICE);

        List<DetectedDocumentType> result = service.detect(fixture.analysis().id(), fixture.owner());

        assertEquals(List.of(DocumentRole.REFERENCE, DocumentRole.INVOICE), result.stream()
                .map(DetectedDocumentType::role).toList());
        assertEquals(2, intelligence.totalClassifyCalls());
        assertEquals(DocumentType.QUOTE, documentRepository.findByIdAndAnalysisId(
                fixture.reference().id(), fixture.analysis().id()).orElseThrow().detectedType());
        assertEquals(DocumentType.INVOICE, documentRepository.findByIdAndAnalysisId(
                fixture.invoice().id(), fixture.analysis().id()).orElseThrow().detectedType());

        Analysis classifying = analysisRepository.findOwnedById(
                fixture.analysis().id(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob job = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
        assertEquals(AnalysisStatus.CLASSIFYING, classifying.status());
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, job.status());
        assertEquals(AnalysisStatus.CLASSIFYING, job.currentStage());
        assertEquals(0, job.attemptCount());
        assertEquals(false, job.retryable());
        assertNull(job.startedAt());
        assertNull(job.completedAt());

        List<DetectedDocumentType> repeated = service.detect(fixture.analysis().id(), fixture.owner());

        assertEquals(result, repeated);
        assertEquals(2, intelligence.totalClassifyCalls());
        assertEquals(2, storage.downloadCalls());
    }

    @Test
    void persistsSuccessfulFirstSuggestionWhenSecondProviderCallFailsAndRetryProcessesOnlyMissingDocument()
            throws IOException {
        Fixture fixture = createAnalysisWithDocuments();
        intelligence.setClassification(fixture.reference().id(), DocumentType.UNKNOWN);
        intelligence.setClassification(fixture.invoice().id(), DocumentType.INVOICE);
        intelligence.configureClassificationFailure(fixture.invoice().id(), Failure.UNAVAILABLE);

        assertThrows(DocumentIntelligenceException.class,
                () -> service.detect(fixture.analysis().id(), fixture.owner()));

        assertEquals(DocumentType.UNKNOWN, documentRepository.findByIdAndAnalysisId(
                fixture.reference().id(), fixture.analysis().id()).orElseThrow().detectedType());
        assertNull(documentRepository.findByIdAndAnalysisId(
                fixture.invoice().id(), fixture.analysis().id()).orElseThrow().detectedType());
        assertEquals(AnalysisStatus.UPLOADING, analysisRepository.findOwnedById(
                fixture.analysis().id(), fixture.owner(), clock.instant()).orElseThrow().status());
        assertEquals(1, intelligence.classifyCalls(fixture.reference().id()));
        assertEquals(1, intelligence.classifyCalls(fixture.invoice().id()));

        intelligence.clearClassificationFailure(fixture.invoice().id());
        service.detect(fixture.analysis().id(), fixture.owner());

        assertEquals(1, intelligence.classifyCalls(fixture.reference().id()));
        assertEquals(2, intelligence.classifyCalls(fixture.invoice().id()));
        assertEquals(AnalysisStatus.CLASSIFYING, analysisRepository.findOwnedById(
                fixture.analysis().id(), fixture.owner(), clock.instant()).orElseThrow().status());
    }

    @Test
    void wrongOwnerIsRejectedBeforePrivateStorageOrAi() throws IOException {
        Fixture fixture = createAnalysisWithDocuments();
        AnalysisOwner wrongOwner = new RegisteredUserOwner(UUID.randomUUID());

        assertThrows(io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException.class,
                () -> service.detect(fixture.analysis().id(), wrongOwner));

        assertEquals(0, storage.downloadCalls());
        assertEquals(0, intelligence.totalClassifyCalls());
    }

    private Fixture createAnalysisWithDocuments() throws IOException {
        Instant now = clock.instant();
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        AnalysisOwner owner = new RegisteredUserOwner(userId);
        Analysis analysis = analysisRepository.create(Analysis.create(
                UUID.randomUUID(), owner, PriceTolerance.exactMatch(), now).transitionToUploading(now));
        analysisJobRepository.create(AnalysisJob.waitingForUser(UUID.randomUUID(), analysis.id(), now)
                .awaitMoreUploads(now));

        String keyPrefix = "ai-detection-test/" + UUID.randomUUID();
        Document reference = createDocument(
                analysis.id(), DocumentRole.REFERENCE, keyPrefix + "/reference", now);
        Document invoice = createDocument(
                analysis.id(), DocumentRole.INVOICE, keyPrefix + "/invoice", now);
        return new Fixture(analysis, owner, reference, invoice);
    }

    private Document createDocument(UUID analysisId, DocumentRole role, String storageKey, Instant now)
            throws IOException {
        Path source = Files.createTempFile("ai-service-test-", ".pdf");
        try {
            Files.write(source, CONTENT);
            storage.store(new StorageObjectUpload(storageKey, source, "application/pdf", CONTENT.length));
        } finally {
            Files.deleteIfExists(source);
        }
        return documentRepository.create(Document.createUploaded(
                UUID.randomUUID(), analysisId, role, role + "-private.pdf", "application/pdf", CONTENT.length,
                1, DatabaseFixtures.sha256(), storageKey, null, now));
    }

    private record Fixture(Analysis analysis, AnalysisOwner owner, Document reference, Document invoice) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestProviderConfiguration {
        @Bean
        @Primary
        FakeDocumentStorage fakeDocumentStorage() {
            return new FakeDocumentStorage();
        }

        @Bean
        @Primary
        FakeDocumentIntelligence fakeDocumentIntelligence() {
            return new FakeDocumentIntelligence();
        }
    }
}
