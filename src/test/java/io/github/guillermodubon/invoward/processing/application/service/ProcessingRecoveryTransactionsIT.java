package io.github.guillermodubon.invoward.processing.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.processing.application.port.ProcessingWakePort;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "invoward.email.provider=disabled",
        "spring.ai.model.chat=none",
        "invoward.processing.recovery-batch-size=2"
})
@Transactional
class ProcessingRecoveryTransactionsIT {

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private RecoverProcessingJobTransaction recoveryTransaction;

    @Autowired
    private RecoverProcessingJobsService recoveryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProcessingWakePort processingWakePort;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void runningAttemptIsRequeuedWithoutResettingAttempts() {
        Fixture fixture = createFixture(
                registeredOwner(), AnalysisStatus.EXTRACTING, AnalysisStatus.EXTRACTING,
                AnalysisJobStatus.RUNNING, 1, Instant.now().minus(1, ChronoUnit.HOURS));

        recoveryTransaction.recover(fixture.analysis().id());

        AnalysisJob recovered = currentJob(fixture);
        assertEquals(AnalysisJobStatus.QUEUED, recovered.status());
        assertEquals(AnalysisStatus.EXTRACTING, recovered.currentStage());
        assertEquals(1, recovered.attemptCount());
        assertFalse(recovered.retryable());
        assertNull(recovered.lastErrorCode());
        assertNull(recovered.lastErrorMessage());
        assertNull(recovered.startedAt());
        assertNull(recovered.completedAt());
        assertEquals(AnalysisStatus.EXTRACTING, currentAnalysis(fixture).status());
    }

    @Test
    void runningAttemptAtConfiguredCapFailsNonretryably() {
        Fixture fixture = createFixture(
                registeredOwner(), AnalysisStatus.MATCHING, AnalysisStatus.MATCHING,
                AnalysisJobStatus.RUNNING, 3, Instant.now().minus(1, ChronoUnit.HOURS));

        recoveryTransaction.recover(fixture.analysis().id());

        Analysis failedAnalysis = currentAnalysis(fixture);
        AnalysisJob failedJob = currentJob(fixture);
        assertEquals(AnalysisStatus.FAILED, failedAnalysis.status());
        assertEquals("PROCESSING_RECOVERY_LIMIT_EXCEEDED", failedAnalysis.failureCode());
        assertFalse(failedAnalysis.retryable());
        assertEquals(AnalysisJobStatus.FAILED, failedJob.status());
        assertEquals(AnalysisStatus.MATCHING, failedJob.currentStage());
        assertEquals(3, failedJob.attemptCount());
        assertEquals("PROCESSING_RECOVERY_LIMIT_EXCEEDED", failedJob.lastErrorCode());
        assertFalse(failedJob.retryable());
        assertNull(failedJob.completedAt());
    }

    @Test
    void normalizesSupportedLegacyWaitingMachineStages() {
        for (AnalysisStatus stage : List.of(
                AnalysisStatus.EXTRACTING,
                AnalysisStatus.MATCHING,
                AnalysisStatus.RECONCILING,
                AnalysisStatus.GENERATING_REPORT)) {
            Fixture fixture = createFixture(
                    registeredOwner(), stage, stage,
                    AnalysisJobStatus.WAITING_FOR_USER, 0, Instant.now().minus(1, ChronoUnit.HOURS));

            recoveryTransaction.recover(fixture.analysis().id());

            AnalysisJob recovered = currentJob(fixture);
            assertEquals(AnalysisJobStatus.QUEUED, recovered.status());
            assertEquals(stage, recovered.currentStage());
            assertEquals(0, recovered.attemptCount());
            assertFalse(recovered.retryable());
            assertNull(recovered.lastErrorCode());
            assertNull(recovered.startedAt());
        }
    }

    @Test
    void queuesLegacyClassificationOnlyWhenOneOfTheRequiredTypesIsMissing() {
        Fixture missingType = createFixture(
                registeredOwner(), AnalysisStatus.CLASSIFYING, AnalysisStatus.CLASSIFYING,
                AnalysisJobStatus.WAITING_FOR_USER, 0, Instant.now().minus(1, ChronoUnit.HOURS));
        persistDocument(missingType, DocumentRole.REFERENCE, DocumentType.QUOTE);
        persistDocument(missingType, DocumentRole.INVOICE, null);

        recoveryTransaction.recover(missingType.analysis().id());

        assertEquals(AnalysisJobStatus.QUEUED, currentJob(missingType).status());

        Fixture complete = createFixture(
                registeredOwner(), AnalysisStatus.CLASSIFYING, AnalysisStatus.CLASSIFYING,
                AnalysisJobStatus.WAITING_FOR_USER, 0, Instant.now().minus(1, ChronoUnit.HOURS));
        persistDocument(complete, DocumentRole.REFERENCE, DocumentType.QUOTE);
        persistDocument(complete, DocumentRole.INVOICE, DocumentType.INVOICE);
        Instant beforeRecovery = currentJob(complete).updatedAt();

        recoveryTransaction.recover(complete.analysis().id());

        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, currentJob(complete).status());
        assertEquals(AnalysisStatus.CLASSIFYING, currentJob(complete).currentStage());
        assertEquals(beforeRecovery, currentJob(complete).updatedAt());
    }

    @Test
    void leavesHumanGateAndFailedJobsUntouched() {
        Fixture gate = createFixture(
                registeredOwner(), AnalysisStatus.AWAITING_CONFIRMATION, AnalysisStatus.AWAITING_CONFIRMATION,
                AnalysisJobStatus.WAITING_FOR_USER, 0, Instant.now().minus(1, ChronoUnit.HOURS));
        Instant gateUpdatedAt = currentJob(gate).updatedAt();

        recoveryTransaction.recover(gate.analysis().id());

        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, currentJob(gate).status());
        assertEquals(AnalysisStatus.AWAITING_CONFIRMATION, currentJob(gate).currentStage());
        assertEquals(gateUpdatedAt, currentJob(gate).updatedAt());
    }

    @Test
    void expiredGuestRunFailsBeforeAnyStageCanBeDispatched() {
        Instant createdAt = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        Fixture fixture = createFixture(
                createExpiredGuestOwner(createdAt), AnalysisStatus.EXTRACTING, AnalysisStatus.EXTRACTING,
                AnalysisJobStatus.RUNNING, 1, createdAt);

        recoveryTransaction.recover(fixture.analysis().id());

        assertEquals(AnalysisStatus.FAILED, currentAnalysis(fixture).status());
        assertEquals("ANALYSIS_EXPIRED", currentAnalysis(fixture).failureCode());
        assertEquals(AnalysisJobStatus.FAILED, currentJob(fixture).status());
        assertEquals("ANALYSIS_EXPIRED", currentJob(fixture).lastErrorCode());
        assertFalse(currentJob(fixture).retryable());
    }

    @Test
    void expiredGuestLegacyStageFailsInsteadOfBeingQueued() {
        Instant createdAt = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        Fixture fixture = createFixture(
                createExpiredGuestOwner(createdAt), AnalysisStatus.MATCHING, AnalysisStatus.MATCHING,
                AnalysisJobStatus.WAITING_FOR_USER, 0, createdAt);

        recoveryTransaction.recover(fixture.analysis().id());

        assertEquals(AnalysisStatus.FAILED, currentAnalysis(fixture).status());
        assertEquals("ANALYSIS_EXPIRED", currentAnalysis(fixture).failureCode());
        assertEquals(AnalysisJobStatus.FAILED, currentJob(fixture).status());
        assertEquals("ANALYSIS_EXPIRED", currentJob(fixture).lastErrorCode());
        assertFalse(currentJob(fixture).retryable());
    }

    @Test
    void inconsistentAnalysisAndRunningJobBecomeSafeNonretryableFailure() {
        Fixture fixture = createFixture(
                registeredOwner(), AnalysisStatus.EXTRACTING, AnalysisStatus.CLASSIFYING,
                AnalysisJobStatus.RUNNING, 1, Instant.now().minus(1, ChronoUnit.HOURS));

        recoveryTransaction.recover(fixture.analysis().id());

        Analysis failedAnalysis = currentAnalysis(fixture);
        AnalysisJob failedJob = currentJob(fixture);
        assertEquals(AnalysisStatus.FAILED, failedAnalysis.status());
        assertEquals("PROCESSING_STATE_CONFLICT", failedAnalysis.failureCode());
        assertFalse(failedAnalysis.retryable());
        assertEquals(AnalysisJobStatus.FAILED, failedJob.status());
        assertEquals(AnalysisStatus.CLASSIFYING, failedJob.currentStage());
        assertEquals("PROCESSING_STATE_CONFLICT", failedJob.lastErrorCode());
        assertEquals("Persisted Analysis and Analysis job states do not match.", failedJob.lastErrorMessage());
        assertFalse(failedJob.retryable());
    }

    @Test
    void recoveryIdScanPagesAreStableAndBounded() {
        createFixture(registeredOwner(), AnalysisStatus.CLASSIFYING, AnalysisStatus.CLASSIFYING,
                AnalysisJobStatus.RUNNING, 1, Instant.now().minus(1, ChronoUnit.HOURS));
        createFixture(registeredOwner(), AnalysisStatus.EXTRACTING, AnalysisStatus.EXTRACTING,
                AnalysisJobStatus.RUNNING, 1, Instant.now().minus(1, ChronoUnit.HOURS));
        createFixture(registeredOwner(), AnalysisStatus.MATCHING, AnalysisStatus.MATCHING,
                AnalysisJobStatus.RUNNING, 1, Instant.now().minus(1, ChronoUnit.HOURS));

        List<UUID> firstPage = analysisJobRepository.findAnalysisIdsByStatusAfter(
                AnalysisJobStatus.RUNNING, null, 2);
        List<UUID> nextPage = analysisJobRepository.findAnalysisIdsByStatusAfter(
                AnalysisJobStatus.RUNNING, firstPage.get(firstPage.size() - 1), 2);

        assertThat(firstPage).hasSize(2).isSortedAccordingTo(Comparator.comparing(UUID::toString));
        assertThat(firstPage).doesNotContainAnyElementsOf(nextPage);
        assertThat(nextPage).allSatisfy(id -> assertThat(id.toString()).isGreaterThan(firstPage.get(1).toString()));
        assertThat(nextPage).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void startupRecoveryRequeuesCommittedRunningAttemptAndWakesIt() {
        Fixture fixture = createFixture(
                registeredOwner(), AnalysisStatus.EXTRACTING, AnalysisStatus.EXTRACTING,
                AnalysisJobStatus.RUNNING, 1, Instant.parse("2000-01-01T00:00:00Z"));
        commitTestTransaction();

        try {
            recoveryService.recoverAtStartup();

            AnalysisJob recovered = currentJob(fixture);
            assertEquals(AnalysisJobStatus.QUEUED, recovered.status());
            assertEquals(AnalysisStatus.EXTRACTING, recovered.currentStage());
            assertEquals(1, recovered.attemptCount());
            assertNull(recovered.startedAt());
            assertEquals(AnalysisStatus.EXTRACTING, currentAnalysis(fixture).status());
            verify(processingWakePort).wake(fixture.analysis().id());
        } finally {
            deleteCommittedAnalysis(fixture.analysis().id());
        }
    }

    @Test
    void startupRecoveryNormalizesLegacyMachineGateAndWakesThePersistedQueue() {
        Fixture fixture = createFixture(
                registeredOwner(), AnalysisStatus.RECONCILING, AnalysisStatus.RECONCILING,
                AnalysisJobStatus.WAITING_FOR_USER, 0, Instant.parse("2000-01-01T00:00:00Z"));
        commitTestTransaction();

        try {
            recoveryService.recoverAtStartup();

            AnalysisJob recovered = currentJob(fixture);
            assertEquals(AnalysisJobStatus.QUEUED, recovered.status());
            assertEquals(AnalysisStatus.RECONCILING, recovered.currentStage());
            assertEquals(0, recovered.attemptCount());
            assertNull(recovered.startedAt());
            verify(processingWakePort).wake(fixture.analysis().id());
        } finally {
            deleteCommittedAnalysis(fixture.analysis().id());
        }
    }

    @Test
    void startupDispatchesOnlyOldestQueuedBatchAndNeverRetriesFailedRows() {
        Instant oldest = Instant.parse("2000-01-01T00:00:00Z");
        Fixture first = createFixture(
                registeredOwner(), AnalysisStatus.CLASSIFYING, AnalysisStatus.CLASSIFYING,
                AnalysisJobStatus.QUEUED, 0, oldest);
        Fixture second = createFixture(
                registeredOwner(), AnalysisStatus.CLASSIFYING, AnalysisStatus.CLASSIFYING,
                AnalysisJobStatus.QUEUED, 0, oldest.plusSeconds(10));
        Fixture third = createFixture(
                registeredOwner(), AnalysisStatus.CLASSIFYING, AnalysisStatus.CLASSIFYING,
                AnalysisJobStatus.QUEUED, 0, oldest.plusSeconds(20));
        Fixture failed = createFailedFixture(oldest.plusSeconds(30));
        commitTestTransaction();

        try {
            recoveryService.recoverAtStartup();

            var wakeOrder = inOrder(processingWakePort);
            wakeOrder.verify(processingWakePort).wake(first.analysis().id());
            wakeOrder.verify(processingWakePort).wake(second.analysis().id());
            verify(processingWakePort, never()).wake(third.analysis().id());
            verify(processingWakePort, never()).wake(failed.analysis().id());
            assertEquals(AnalysisJobStatus.QUEUED, currentJob(first).status());
            assertEquals(AnalysisJobStatus.QUEUED, currentJob(second).status());
            assertEquals(AnalysisJobStatus.QUEUED, currentJob(third).status());
            assertEquals(AnalysisJobStatus.FAILED, currentJob(failed).status());
            assertEquals(AnalysisStatus.FAILED, currentAnalysis(failed).status());
            assertEquals(1, currentJob(failed).attemptCount());
        } finally {
            deleteCommittedAnalysis(first.analysis().id());
            deleteCommittedAnalysis(second.analysis().id());
            deleteCommittedAnalysis(third.analysis().id());
            deleteCommittedAnalysis(failed.analysis().id());
        }
    }

    private Fixture createFixture(
            AnalysisOwner owner,
            AnalysisStatus analysisStage,
            AnalysisStatus jobStage,
            AnalysisJobStatus jobStatus,
            int attempts,
            Instant createdAt) {
        Instant base = createdAt.truncatedTo(ChronoUnit.MICROS);
        UUID analysisId = UUID.randomUUID();
        Analysis analysis = Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), base)
                .transitionToUploading(base.plusSeconds(1))
                .beginClassification(base.plusSeconds(2));
        if (analysisStage == AnalysisStatus.EXTRACTING) {
            analysis = analysis.beginExtraction(base.plusSeconds(3));
        } else if (analysisStage == AnalysisStatus.AWAITING_CONFIRMATION) {
            analysis = analysis.awaitExtractionConfirmation(base.plusSeconds(3));
        } else if (analysisStage == AnalysisStatus.MATCHING) {
            analysis = analysis.awaitExtractionConfirmation(base.plusSeconds(3))
                    .confirmExtraction("QUOTE", "Q-1", "I-1", "Example Supplier", "USD",
                            BigDecimal.TEN, BigDecimal.TEN, base.plusSeconds(4));
        } else if (analysisStage == AnalysisStatus.AWAITING_MATCH_REVIEW) {
            analysis = analysis.awaitExtractionConfirmation(base.plusSeconds(3))
                    .confirmExtraction("QUOTE", "Q-1", "I-1", "Example Supplier", "USD",
                            BigDecimal.TEN, BigDecimal.TEN, base.plusSeconds(4))
                    .awaitMatchReview(base.plusSeconds(5));
        } else if (analysisStage == AnalysisStatus.RECONCILING) {
            analysis = analysis.awaitExtractionConfirmation(base.plusSeconds(3))
                    .confirmExtraction("QUOTE", "Q-1", "I-1", "Example Supplier", "USD",
                            BigDecimal.TEN, BigDecimal.TEN, base.plusSeconds(4))
                    .beginReconciliation(base.plusSeconds(5));
        } else if (analysisStage == AnalysisStatus.GENERATING_REPORT) {
            analysis = analysis.awaitExtractionConfirmation(base.plusSeconds(3))
                    .confirmExtraction("QUOTE", "Q-1", "I-1", "Example Supplier", "USD",
                            BigDecimal.TEN, BigDecimal.TEN, base.plusSeconds(4))
                    .beginReconciliation(base.plusSeconds(5))
                    .completeReconciliation(AnalysisReconciliationStatus.MATCHED, BigDecimal.ZERO,
                            base.plusSeconds(6));
        }
        Analysis persistedAnalysis = analysisRepository.create(analysis);

        Instant startedAt = jobStatus == AnalysisJobStatus.RUNNING ? base.plusSeconds(8) : null;
        Instant jobUpdatedAt = startedAt == null ? base.plusSeconds(8) : startedAt;
        AnalysisJob job = new AnalysisJob(
                UUID.randomUUID(), analysisId, jobStatus, jobStage,
                attempts, false, null, null, startedAt, null, base, jobUpdatedAt);
        AnalysisJob persistedJob = analysisJobRepository.create(job);
        return new Fixture(persistedAnalysis, persistedJob);
    }

    private Fixture createFailedFixture(Instant createdAt) {
        Instant base = createdAt.truncatedTo(ChronoUnit.MICROS);
        AnalysisOwner owner = registeredOwner();
        UUID analysisId = UUID.randomUUID();
        Analysis analysis = Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), base)
                .transitionToUploading(base.plusSeconds(1))
                .beginClassification(base.plusSeconds(2))
                .failProcessing(AnalysisStatus.CLASSIFYING, "PROCESSING_FAILED",
                        "Processing could not be completed.", true, base.plusSeconds(4));
        AnalysisJob job = AnalysisJob.waitingForUser(UUID.randomUUID(), analysisId, base)
                .awaitMoreUploads(base.plusSeconds(1))
                .queueClassification(base.plusSeconds(2))
                .startQueuedAttempt(base.plusSeconds(3), 3)
                .failCurrentAttempt("PROCESSING_FAILED", "Safe processing failure.", true,
                        3, base.plusSeconds(4));
        return new Fixture(analysisRepository.create(analysis), analysisJobRepository.create(job));
    }

    private static void commitTestTransaction() {
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }

    private void deleteCommittedAnalysis(UUID analysisId) {
        jdbcTemplate.update("DELETE FROM invoward.analyses WHERE id = ?", analysisId);
    }

    private void persistDocument(Fixture fixture, DocumentRole role, DocumentType detectedType) {
        Analysis analysis = currentAnalysis(fixture);
        Document document = Document.createUploaded(
                UUID.randomUUID(), analysis.id(), role,
                role.name().toLowerCase() + ".pdf", "application/pdf", 1, 1,
                "0".repeat(64), "tests/" + analysis.id() + "/" + role.name().toLowerCase() + ".pdf",
                analysis.expiresAt(), analysis.createdAt().plusSeconds(3));
        if (detectedType != null) {
            document = document.withDetectedType(detectedType);
        }
        documentRepository.create(document);
    }

    private AnalysisOwner registeredOwner() {
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        return new RegisteredUserOwner(userId);
    }

    private AnalysisOwner createExpiredGuestOwner(Instant createdAt) {
        Instant expiresAt = createdAt.plusSeconds(30);
        UUID guestSessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO invoward.guest_sessions (id, expires_at, last_seen_at, created_at)
                VALUES (?, ?, ?, ?)
                """, guestSessionId, Timestamp.from(expiresAt), Timestamp.from(createdAt), Timestamp.from(createdAt));
        return new GuestSessionOwner(guestSessionId, expiresAt);
    }

    private Analysis currentAnalysis(Fixture fixture) {
        return analysisRepository.findByIdForProcessing(fixture.analysis().id()).orElseThrow();
    }

    private AnalysisJob currentJob(Fixture fixture) {
        return analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
    }

    private record Fixture(Analysis analysis, AnalysisJob job) {
    }
}
