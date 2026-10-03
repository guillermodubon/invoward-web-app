package io.github.guillermodubon.invoward.document.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.model.IncomingDocumentUpload;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import io.github.guillermodubon.invoward.support.storage.FakeDocumentStorage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "invoward.email.provider=disabled",
        "invoward.documents.max-combined-size=1KB"
})
@Import(UploadDocumentServiceIT.TestStorageConfiguration.class)
class UploadDocumentServiceIT {

    @Autowired
    private UploadDocumentService uploadService;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private FakeDocumentStorage storage;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void clearFakeStorage() {
        storage.clear();
    }

    @Test
    void uploadsRegisteredDocumentAndPersistsMetadataAndWaitingStateTogether() throws Exception {
        TestAnalysis testAnalysis = createRegisteredAnalysis(true);
        byte[] bytes = validPdf();

        Document uploaded = uploadService.upload(
                testAnalysis.analysis().id(), testAnalysis.owner(), DocumentRole.REFERENCE, incoming(bytes));

        Document persisted = documentRepository.findByIdAndAnalysisId(
                uploaded.id(), testAnalysis.analysis().id()).orElseThrow();
        assertEquals("reference-original.pdf", persisted.originalFilename());
        assertEquals("application/pdf", persisted.contentType());
        assertEquals((long) bytes.length, persisted.sizeBytes());
        assertEquals(1, persisted.pageCount());
        assertEquals(sha256(bytes), persisted.sha256());
        assertEquals(DocumentRole.REFERENCE, persisted.role());
        assertNull(persisted.detectedType());
        assertNull(persisted.confirmedType());
        assertNull(persisted.expiresAt());
        assertFalse(persisted.storageKey().contains(persisted.originalFilename()));
        assertTrue(persisted.storageKey().startsWith("users/"));
        assertEquals(List.of(persisted), documentRepository.findByAnalysisId(testAnalysis.analysis().id()));
        assertEquals(bytes.length, storage.storedBytes(persisted.storageKey()).orElseThrow().length);
        assertEquals("application/pdf", storage.storedContentType(persisted.storageKey()).orElseThrow());

        Analysis updatedAnalysis = analysisRepository.findOwnedById(
                testAnalysis.analysis().id(), testAnalysis.owner(), clock.instant()).orElseThrow();
        assertEquals(AnalysisStatus.UPLOADING, updatedAnalysis.status());
        assertEquals(1, updatedAnalysis.version());
        AnalysisJob updatedJob = analysisJobRepository.findByAnalysisId(testAnalysis.analysis().id()).orElseThrow();
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, updatedJob.status());
        assertEquals(AnalysisStatus.UPLOADING, updatedJob.currentStage());
        assertEquals(0, updatedJob.attemptCount());
        assertFalse(updatedJob.retryable());
        assertNull(updatedJob.startedAt());
        assertNull(updatedJob.completedAt());
    }

    @Test
    void persistsGuestAnalysisExpiryOnDocumentAndStoresItsBytes() throws Exception {
        UUID guestSessionId = jdbcTemplate.execute(
                (ConnectionCallback<UUID>) DatabaseFixtures::insertGuestSession);
        Instant expiry = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM invoward.guest_sessions WHERE id = ?",
                (result, row) -> result.getTimestamp(1).toInstant(), guestSessionId);
        AnalysisOwner owner = new GuestSessionOwner(guestSessionId, expiry);
        TestAnalysis testAnalysis = createAnalysis(owner, true);
        byte[] bytes = validPdf();

        Document uploaded = uploadService.upload(
                testAnalysis.analysis().id(), owner, DocumentRole.INVOICE, incoming(bytes));

        Document persisted = documentRepository.findByIdAndAnalysisId(
                uploaded.id(), testAnalysis.analysis().id()).orElseThrow();
        assertEquals(expiry, persisted.expiresAt());
        assertEquals(bytes.length, storage.storedBytes(persisted.storageKey()).orElseThrow().length);
        assertEquals(AnalysisStatus.UPLOADING, analysisRepository.findOwnedById(
                testAnalysis.analysis().id(), owner, clock.instant()).orElseThrow().status());
    }

    @Test
    void databaseRollbackCompensatesPreviouslyStoredObject() throws Exception {
        TestAnalysis testAnalysis = createRegisteredAnalysis(false);
        byte[] bytes = validPdf();

        assertThrows(IllegalStateException.class, () -> uploadService.upload(
                testAnalysis.analysis().id(), testAnalysis.owner(), DocumentRole.REFERENCE, incoming(bytes)));

        assertTrue(documentRepository.findByAnalysisId(testAnalysis.analysis().id()).isEmpty());
        Analysis unchanged = analysisRepository.findOwnedById(
                testAnalysis.analysis().id(), testAnalysis.owner(), clock.instant()).orElseThrow();
        assertEquals(AnalysisStatus.CREATED, unchanged.status());
        assertEquals(0, unchanged.version());
        assertTrue(analysisJobRepository.findByAnalysisId(testAnalysis.analysis().id()).isEmpty());
        assertEquals(0, storage.storedObjectCount());
    }

    private TestAnalysis createRegisteredAnalysis(boolean createJob) {
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        return createAnalysis(new RegisteredUserOwner(userId), createJob);
    }

    private TestAnalysis createAnalysis(AnalysisOwner owner, boolean createJob) {
        Instant createdAt = clock.instant().minusSeconds(1);
        Analysis analysis = analysisRepository.create(Analysis.create(
                UUID.randomUUID(), owner, PriceTolerance.exactMatch(), createdAt));
        if (createJob) {
            analysisJobRepository.create(AnalysisJob.waitingForUser(
                    UUID.randomUUID(), analysis.id(), createdAt));
        }
        return new TestAnalysis(analysis, owner);
    }

    private static IncomingDocumentUpload incoming(byte[] bytes) {
        return new IncomingDocumentUpload(
                "reference-original.pdf", "application/pdf", bytes.length, new ByteArrayInputStream(bytes));
    }

    private static byte[] validPdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestStorageConfiguration {

        @Bean
        @Primary
        FakeDocumentStorage fakeDocumentStorage() {
            return new FakeDocumentStorage();
        }
    }

    private record TestAnalysis(Analysis analysis, AnalysisOwner owner) {
    }
}
