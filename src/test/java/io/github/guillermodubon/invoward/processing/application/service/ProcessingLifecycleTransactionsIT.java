package io.github.guillermodubon.invoward.processing.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.api.model.AnalysisStatusResponse;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisStatusService;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.processing.application.exception.ProcessingRetryNotAllowedException;
import io.github.guillermodubon.invoward.processing.application.model.ProcessingClaim;
import io.github.guillermodubon.invoward.processing.application.model.ProcessingFailure;
import io.github.guillermodubon.invoward.processing.application.port.ProcessingFailureClassifier;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
@Transactional
class ProcessingLifecycleTransactionsIT {

    private static final String SAFE_FAILURE_CODE = "AI_PROVIDER_UNAVAILABLE";
    private static final String SAFE_INTERNAL_MESSAGE = "AI provider unavailable.";
    private static final String SAFE_USER_MESSAGE = "Processing is temporarily unavailable.";

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private ClaimProcessingJobTransaction claimTransaction;

    @Autowired
    private FailProcessingJobTransaction failTransaction;

    @Autowired
    private RetryAnalysisProcessingService retryService;

    @Autowired
    private ProcessingFailureClassifier failureClassifier;

    @Autowired
    private GetAnalysisStatusService getAnalysisStatusService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void claimsQueuedMachineStageExactlyOnceAndIncrementsAttempt() {
        Fixture fixture = createQueuedFixture();

        Optional<ProcessingClaim> claim = claimTransaction.claim(fixture.analysis().id());

        assertThat(claim).isPresent();
        assertEquals(fixture.analysis().id(), claim.orElseThrow().analysisId());
        assertEquals(fixture.owner(), claim.orElseThrow().owner());
        assertEquals(AnalysisStatus.CLASSIFYING, claim.orElseThrow().stage());
        assertEquals(1, claim.orElseThrow().attemptNumber());
        assertThat(claimTransaction.claim(fixture.analysis().id())).isEmpty();

        AnalysisJob persisted = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
        assertEquals(AnalysisJobStatus.RUNNING, persisted.status());
        assertEquals(AnalysisStatus.CLASSIFYING, persisted.currentStage());
        assertEquals(1, persisted.attemptCount());
        assertFalse(persisted.retryable());
        assertNull(persisted.lastErrorCode());
        assertNull(persisted.lastErrorMessage());
        assertTrue(persisted.startedAt() != null);
        assertNull(persisted.completedAt());
    }

    @Test
    void wrongAnalysisStageDoesNotClaimOrMutateQueuedJob() {
        Fixture fixture = createQueuedFixture();
        analysisRepository.update(fixture.analysis().beginExtraction(fixture.createdAt().plusSeconds(3)));

        assertThat(claimTransaction.claim(fixture.analysis().id())).isEmpty();

        AnalysisJob unchanged = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
        assertEquals(AnalysisJobStatus.QUEUED, unchanged.status());
        assertEquals(AnalysisStatus.CLASSIFYING, unchanged.currentStage());
        assertEquals(0, unchanged.attemptCount());
        assertNull(unchanged.startedAt());
    }

    @Test
    void expiredGuestIsTerminalizedBeforeAnyAttemptStarts() {
        Fixture fixture = createExpiredGuestFixture();

        assertThat(claimTransaction.claim(fixture.analysis().id())).isEmpty();

        Analysis failedAnalysis = analysisRepository.findByIdForProcessing(fixture.analysis().id()).orElseThrow();
        AnalysisJob failedJob = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
        assertEquals(AnalysisStatus.FAILED, failedAnalysis.status());
        assertEquals("ANALYSIS_EXPIRED", failedAnalysis.failureCode());
        assertEquals("This guest analysis has expired.", failedAnalysis.failureUserMessage());
        assertFalse(failedAnalysis.retryable());
        assertEquals(AnalysisJobStatus.FAILED, failedJob.status());
        assertEquals(AnalysisStatus.CLASSIFYING, failedJob.currentStage());
        assertEquals(0, failedJob.attemptCount());
        assertEquals("ANALYSIS_EXPIRED", failedJob.lastErrorCode());
        assertFalse(failedJob.retryable());
        assertNull(failedJob.startedAt());
        assertNull(failedJob.completedAt());
    }

    @Test
    void failurePersistsOnlyForTheExactCurrentStageAttempt() {
        Fixture fixture = createQueuedFixture();
        ProcessingClaim claim = claimTransaction.claim(fixture.analysis().id()).orElseThrow();

        assertTrue(failTransaction.fail(claim, retryableFailure()));

        Analysis failedAnalysis = analysisRepository.findByIdForProcessing(fixture.analysis().id()).orElseThrow();
        AnalysisJob failedJob = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
        assertEquals(AnalysisStatus.FAILED, failedAnalysis.status());
        assertEquals(SAFE_FAILURE_CODE, failedAnalysis.failureCode());
        assertEquals(SAFE_USER_MESSAGE, failedAnalysis.failureUserMessage());
        assertTrue(failedAnalysis.retryable());
        assertEquals(AnalysisJobStatus.FAILED, failedJob.status());
        assertEquals(AnalysisStatus.CLASSIFYING, failedJob.currentStage());
        assertEquals(1, failedJob.attemptCount());
        assertEquals(SAFE_FAILURE_CODE, failedJob.lastErrorCode());
        assertEquals(SAFE_INTERNAL_MESSAGE, failedJob.lastErrorMessage());
        assertTrue(failedJob.retryable());
        assertNull(failedJob.completedAt());
    }

    @Test
    void rawExceptionSentinelNeverReachesPersistedOrPublicFailureMessages() {
        String rawExceptionSentinel = "provider-secret-sentinel-7e91";
        Fixture fixture = createQueuedFixture();
        ProcessingClaim claim = claimTransaction.claim(fixture.analysis().id()).orElseThrow();
        ProcessingFailure classified = failureClassifier.classify(
                new IllegalStateException(rawExceptionSentinel)).orElseThrow();

        assertTrue(failTransaction.fail(claim, classified));

        Analysis failedAnalysis = analysisRepository.findByIdForProcessing(fixture.analysis().id()).orElseThrow();
        AnalysisJob failedJob = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
        assertFalse(failedAnalysis.failureUserMessage().contains(rawExceptionSentinel));
        assertFalse(failedJob.lastErrorMessage().contains(rawExceptionSentinel));

        AnalysisStatusResponse publicResponse = AnalysisStatusResponse.from(
                getAnalysisStatusService.getStatus(fixture.analysis().id(), fixture.owner()));
        assertFalse(publicResponse.failureMessage().contains(rawExceptionSentinel));
        assertFalse(publicResponse.toString().contains(rawExceptionSentinel));
    }

    @Test
    void staleFailureCannotOverwriteARetriedAndReclaimedAttempt() {
        Fixture fixture = createQueuedFixture();
        ProcessingClaim firstClaim = claimTransaction.claim(fixture.analysis().id()).orElseThrow();
        assertTrue(failTransaction.fail(firstClaim, retryableFailure()));
        retryService.retry(fixture.analysis().id(), fixture.owner());
        ProcessingClaim secondClaim = claimTransaction.claim(fixture.analysis().id()).orElseThrow();
        assertEquals(2, secondClaim.attemptNumber());

        assertFalse(failTransaction.fail(firstClaim, retryableFailure()));

        Analysis currentAnalysis = analysisRepository.findByIdForProcessing(fixture.analysis().id()).orElseThrow();
        AnalysisJob currentJob = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
        assertEquals(AnalysisStatus.CLASSIFYING, currentAnalysis.status());
        assertNull(currentAnalysis.failureCode());
        assertEquals(AnalysisJobStatus.RUNNING, currentJob.status());
        assertEquals(2, currentJob.attemptCount());
        assertNull(currentJob.lastErrorCode());
    }

    @Test
    void retryQueuesTheSameFailedStageAndPreservesAttempts() {
        Fixture fixture = createQueuedFixture();
        ProcessingClaim claim = claimTransaction.claim(fixture.analysis().id()).orElseThrow();
        assertTrue(failTransaction.fail(claim, retryableFailure()));

        AnalysisStatusSnapshot result = retryService.retry(fixture.analysis().id(), fixture.owner());

        assertEquals(AnalysisStatus.CLASSIFYING, result.status());
        assertEquals(AnalysisJobStatus.QUEUED, result.jobStatus());
        assertEquals(AnalysisStatus.CLASSIFYING, result.currentStage());
        assertFalse(result.retryable());
        assertNull(result.failureCode());
        assertNull(result.failureMessage());
        AnalysisJob queued = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
        assertEquals(1, queued.attemptCount());
        assertNull(queued.lastErrorCode());
        assertNull(queued.lastErrorMessage());
        assertNull(queued.startedAt());
        assertNull(queued.completedAt());
    }

    @Test
    void rejectsNonretryableAndAttemptCappedJobs() {
        Fixture nonretryableFixture = createQueuedFixture();
        ProcessingClaim firstClaim = claimTransaction.claim(nonretryableFixture.analysis().id()).orElseThrow();
        assertTrue(failTransaction.fail(firstClaim, new ProcessingFailure(
                SAFE_FAILURE_CODE, SAFE_INTERNAL_MESSAGE, SAFE_USER_MESSAGE, false)));
        assertThrows(ProcessingRetryNotAllowedException.class,
                () -> retryService.retry(nonretryableFixture.analysis().id(), nonretryableFixture.owner()));

        Fixture cappedFixture = createQueuedFixture();
        for (int attempt = 1; attempt <= 3; attempt++) {
            ProcessingClaim claim = claimTransaction.claim(cappedFixture.analysis().id()).orElseThrow();
            assertEquals(attempt, claim.attemptNumber());
            assertTrue(failTransaction.fail(claim, retryableFailure()));
            if (attempt < 3) {
                retryService.retry(cappedFixture.analysis().id(), cappedFixture.owner());
            }
        }
        Analysis cappedAnalysis = analysisRepository.findByIdForProcessing(cappedFixture.analysis().id()).orElseThrow();
        AnalysisJob cappedJob = analysisJobRepository.findByAnalysisId(cappedFixture.analysis().id()).orElseThrow();
        assertFalse(cappedAnalysis.retryable());
        assertFalse(cappedJob.retryable());
        assertEquals(3, cappedJob.attemptCount());
        assertThrows(ProcessingRetryNotAllowedException.class,
                () -> retryService.retry(cappedFixture.analysis().id(), cappedFixture.owner()));
        assertThat(claimTransaction.claim(cappedFixture.analysis().id())).isEmpty();
    }

    @Test
    void retryOfUnknownOrUnownedAnalysisUsesTheSafeNotFoundSignal() {
        assertThrows(AnalysisNotFoundException.class,
                () -> retryService.retry(UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID())));
    }

    private Fixture createQueuedFixture() {
        Instant createdAt = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        return createQueuedFixture(new RegisteredUserOwner(userId), createdAt);
    }

    private Fixture createExpiredGuestFixture() {
        Instant createdAt = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = createdAt.plus(1, ChronoUnit.HOURS);
        UUID guestSessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO invoward.guest_sessions (id, expires_at, last_seen_at, created_at)
                VALUES (?, ?, ?, ?)
                """, guestSessionId, Timestamp.from(expiresAt), Timestamp.from(createdAt), Timestamp.from(createdAt));
        return createQueuedFixture(new GuestSessionOwner(guestSessionId, expiresAt), createdAt);
    }

    private Fixture createQueuedFixture(AnalysisOwner owner, Instant createdAt) {
        UUID analysisId = UUID.randomUUID();
        Analysis analysis = analysisRepository.create(
                Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), createdAt));
        analysis = analysisRepository.update(analysis.transitionToUploading(createdAt.plusSeconds(1)));
        analysis = analysisRepository.update(analysis.beginClassification(createdAt.plusSeconds(2)));

        AnalysisJob job = AnalysisJob.waitingForUser(UUID.randomUUID(), analysisId, createdAt)
                .awaitMoreUploads(createdAt.plusSeconds(1))
                .queueClassification(createdAt.plusSeconds(2));
        AnalysisJob persistedJob = analysisJobRepository.create(job);
        return new Fixture(analysis, owner, persistedJob, createdAt);
    }

    private static ProcessingFailure retryableFailure() {
        return new ProcessingFailure(
                SAFE_FAILURE_CODE, SAFE_INTERNAL_MESSAGE, SAFE_USER_MESSAGE, true);
    }

    private record Fixture(Analysis analysis, AnalysisOwner owner, AnalysisJob job, Instant createdAt) {
    }
}
