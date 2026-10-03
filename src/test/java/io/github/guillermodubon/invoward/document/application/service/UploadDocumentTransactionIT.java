package io.github.guillermodubon.invoward.document.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
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
import io.github.guillermodubon.invoward.document.application.exception.AnalysisDocumentSizeLimitExceededException;
import io.github.guillermodubon.invoward.document.application.exception.AnalysisDocumentsLockedException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentRoleAlreadyExistsException;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "invoward.email.provider=disabled",
        "invoward.documents.max-combined-size=1KB"
})
class UploadDocumentTransactionIT {

    @Autowired
    private UploadDocumentTransaction transaction;

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

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void persistsDocumentAndMovesAnalysisAndItsIdleJobToUploading() {
        TestAnalysis testAnalysis = createAnalysis(true);
        Document incoming = document(testAnalysis.analysis(), DocumentRole.REFERENCE, 300);

        Document persisted = transaction.persist(testAnalysis.owner(), incoming);

        assertEquals(incoming, persisted);
        List<Document> storedDocuments = documentRepository.findByAnalysisId(testAnalysis.analysis().id());
        assertEquals(1, storedDocuments.size());
        assertEquals(incoming.id(), storedDocuments.getFirst().id());
        assertEquals(incoming.sizeBytes(), storedDocuments.getFirst().sizeBytes());
        Analysis updatedAnalysis = ownedAnalysis(testAnalysis);
        assertEquals(AnalysisStatus.UPLOADING, updatedAnalysis.status());
        assertEquals(1, updatedAnalysis.version());
        AnalysisJob updatedJob = analysisJobRepository.findByAnalysisId(testAnalysis.analysis().id())
                .orElseThrow();
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, updatedJob.status());
        assertEquals(AnalysisStatus.UPLOADING, updatedJob.currentStage());
        assertEquals(0, updatedJob.attemptCount());
        assertFalse(updatedJob.retryable());
        assertNull(updatedJob.startedAt());
        assertNull(updatedJob.completedAt());
    }

    @Test
    void acceptsBothRolesAndCombinedSizeExactlyAtTheConfiguredLimit() {
        TestAnalysis testAnalysis = createAnalysis(true);
        transaction.persist(testAnalysis.owner(), document(testAnalysis.analysis(), DocumentRole.REFERENCE, 900));

        transaction.persist(testAnalysis.owner(), document(testAnalysis.analysis(), DocumentRole.INVOICE, 124));

        assertEquals(1_024L, documentRepository.sumSizeBytesByAnalysisId(testAnalysis.analysis().id()));
        assertEquals(2, documentRepository.findByAnalysisId(testAnalysis.analysis().id()).size());
        assertEquals(AnalysisStatus.UPLOADING, ownedAnalysis(testAnalysis).status());
    }

    @Test
    void rejectsDuplicateRoleWithoutChangingPersistedState() {
        TestAnalysis testAnalysis = createAnalysis(true);
        transaction.persist(testAnalysis.owner(), document(testAnalysis.analysis(), DocumentRole.REFERENCE, 100));
        Analysis before = ownedAnalysis(testAnalysis);
        AnalysisJob jobBefore = analysisJobRepository.findByAnalysisId(testAnalysis.analysis().id()).orElseThrow();

        assertThrows(DocumentRoleAlreadyExistsException.class,
                () -> transaction.persist(testAnalysis.owner(),
                        document(testAnalysis.analysis(), DocumentRole.REFERENCE, 100)));

        assertEquals(1, documentRepository.findByAnalysisId(testAnalysis.analysis().id()).size());
        assertEquals(before, ownedAnalysis(testAnalysis));
        assertEquals(jobBefore, analysisJobRepository.findByAnalysisId(testAnalysis.analysis().id()).orElseThrow());
    }

    @Test
    void serializesConcurrentSameRoleUploadsAndReturnsSafeConflictToLoser() throws Exception {
        TestAnalysis testAnalysis = createAnalysis(true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                Document incoming = document(testAnalysis.analysis(), DocumentRole.REFERENCE, 100);
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent upload start timed out");
                    }
                    try {
                        transaction.persist(testAnalysis.owner(), incoming);
                        return true;
                    } catch (DocumentRoleAlreadyExistsException expected) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            boolean first = attempts.get(0).get(15, TimeUnit.SECONDS);
            boolean second = attempts.get(1).get(15, TimeUnit.SECONDS);

            assertNotEquals(first, second);
            assertEquals(1, documentRepository.findByAnalysisId(testAnalysis.analysis().id()).size());
            Analysis updatedAnalysis = ownedAnalysis(testAnalysis);
            assertEquals(AnalysisStatus.UPLOADING, updatedAnalysis.status());
            assertEquals(1, updatedAnalysis.version());
            AnalysisJob updatedJob = analysisJobRepository.findByAnalysisId(testAnalysis.analysis().id())
                    .orElseThrow();
            assertEquals(AnalysisJobStatus.WAITING_FOR_USER, updatedJob.status());
            assertEquals(AnalysisStatus.UPLOADING, updatedJob.currentStage());
            assertEquals(0, updatedJob.attemptCount());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsUploadWhenCombinedBytesWouldExceedLimit() {
        TestAnalysis testAnalysis = createAnalysis(true);
        transaction.persist(testAnalysis.owner(), document(testAnalysis.analysis(), DocumentRole.REFERENCE, 900));
        Analysis before = ownedAnalysis(testAnalysis);
        AnalysisJob jobBefore = analysisJobRepository.findByAnalysisId(testAnalysis.analysis().id()).orElseThrow();

        assertThrows(AnalysisDocumentSizeLimitExceededException.class,
                () -> transaction.persist(testAnalysis.owner(),
                        document(testAnalysis.analysis(), DocumentRole.INVOICE, 125)));

        assertEquals(1, documentRepository.findByAnalysisId(testAnalysis.analysis().id()).size());
        assertEquals(before, ownedAnalysis(testAnalysis));
        assertEquals(jobBefore, analysisJobRepository.findByAnalysisId(testAnalysis.analysis().id()).orElseThrow());
    }

    @Test
    void rejectsAnalysisOutsideUploadWindowWithoutPersistingMetadata() {
        TestAnalysis testAnalysis = createAnalysis(true);
        jdbcTemplate.update("UPDATE invoward.analyses SET status = 'CLASSIFYING' WHERE id = ?",
                testAnalysis.analysis().id());

        assertThrows(AnalysisDocumentsLockedException.class,
                () -> transaction.persist(testAnalysis.owner(),
                        document(testAnalysis.analysis(), DocumentRole.REFERENCE, 100)));

        assertTrue(documentRepository.findByAnalysisId(testAnalysis.analysis().id()).isEmpty());
    }

    @Test
    void returnsSameNotFoundForAnalysisOwnedByAnotherIdentity() {
        TestAnalysis testAnalysis = createAnalysis(true);

        assertThrows(AnalysisNotFoundException.class,
                () -> transaction.persist(new RegisteredUserOwner(UUID.randomUUID()),
                        document(testAnalysis.analysis(), DocumentRole.REFERENCE, 100)));

        assertTrue(documentRepository.findByAnalysisId(testAnalysis.analysis().id()).isEmpty());
        assertEquals(AnalysisStatus.CREATED, ownedAnalysis(testAnalysis).status());
    }

    @Test
    void rollsBackDocumentAndAnalysisWhenTheRequiredJobIsMissing() {
        TestAnalysis testAnalysis = createAnalysis(false);

        assertThrows(IllegalStateException.class,
                () -> transaction.persist(testAnalysis.owner(),
                        document(testAnalysis.analysis(), DocumentRole.REFERENCE, 100)));

        assertTrue(documentRepository.findByAnalysisId(testAnalysis.analysis().id()).isEmpty());
        Analysis unchanged = ownedAnalysis(testAnalysis);
        assertEquals(AnalysisStatus.CREATED, unchanged.status());
        assertEquals(0, unchanged.version());
    }

    @Test
    void copiesFixedGuestAnalysisExpiryToPersistedDocument() {
        UUID guestSessionId = jdbcTemplate.execute(
                (ConnectionCallback<UUID>) DatabaseFixtures::insertGuestSession);
        Instant expiry = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM invoward.guest_sessions WHERE id = ?",
                (result, row) -> result.getTimestamp(1).toInstant(), guestSessionId);
        AnalysisOwner owner = new GuestSessionOwner(guestSessionId, expiry);
        TestAnalysis testAnalysis = createAnalysis(owner, true);

        Document persisted = transaction.persist(owner,
                document(testAnalysis.analysis(), DocumentRole.REFERENCE, 100));

        assertEquals(expiry, persisted.expiresAt());
    }

    private TestAnalysis createAnalysis(boolean createJob) {
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

    private Analysis ownedAnalysis(TestAnalysis testAnalysis) {
        return analysisRepository.findOwnedById(
                testAnalysis.analysis().id(), testAnalysis.owner(), clock.instant()).orElseThrow();
    }

    private Document document(Analysis analysis, DocumentRole role, long sizeBytes) {
        return Document.createUploaded(
                UUID.randomUUID(),
                analysis.id(),
                role,
                role == DocumentRole.REFERENCE ? "reference.pdf" : "invoice.pdf",
                "application/pdf",
                sizeBytes,
                1,
                "a".repeat(64),
                "test/" + UUID.randomUUID(),
                analysis.expiresAt(),
                clock.instant());
    }

    private record TestAnalysis(Analysis analysis, AnalysisOwner owner) {
    }
}
