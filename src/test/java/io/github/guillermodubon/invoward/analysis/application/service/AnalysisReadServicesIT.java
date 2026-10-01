package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "invoward.email.provider=disabled")
@Transactional
class AnalysisReadServicesIT {

    @Autowired
    private GetAnalysisService getAnalysisService;

    @Autowired
    private GetAnalysisStatusService getAnalysisStatusService;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void registeredOwnerCanReadAnalysisAndItsSafeStatus() throws Exception {
        Instant now = Instant.now();
        RegisteredUserOwner owner = new RegisteredUserOwner(insertUser());
        Analysis analysis = persistAnalysis(owner, now);

        assertEquals(analysis, getAnalysisService.get(analysis.id(), owner));
        AnalysisStatusSnapshot status = getAnalysisStatusService.getStatus(analysis.id(), owner);
        assertEquals(analysis.id(), status.analysisId());
        assertEquals(AnalysisStatus.CREATED, status.status());
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, status.jobStatus());
        assertEquals(AnalysisStatus.CREATED, status.currentStage());
        assertEquals(analysis.updatedAt(), status.updatedAt());
    }

    @Test
    void anotherRegisteredOwnerGetsTheSameNotFoundSignalForBothReads() throws Exception {
        Instant now = Instant.now();
        Analysis analysis = persistAnalysis(
                new RegisteredUserOwner(insertUser()), now);
        RegisteredUserOwner otherOwner = new RegisteredUserOwner(insertUser());

        assertNotFound(() -> getAnalysisService.get(analysis.id(), otherOwner));
        assertNotFound(() -> getAnalysisStatusService.getStatus(analysis.id(), otherOwner));
    }

    @Test
    void guestOwnerReadsRequireMatchingSessionAndUnexpiredAnalysis() throws Exception {
        Instant now = Instant.now();
        UUID guestSessionId = insertGuestSession();
        GuestSessionOwner owner = new GuestSessionOwner(guestSessionId, now.plusSeconds(3600));
        Analysis analysis = persistAnalysis(owner, now);
        GuestSessionOwner otherOwner = new GuestSessionOwner(UUID.randomUUID(), owner.expiresAt());

        assertEquals(analysis, getAnalysisService.get(analysis.id(), owner));
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER,
                getAnalysisStatusService.getStatus(analysis.id(), owner).jobStatus());
        assertNotFound(() -> getAnalysisService.get(analysis.id(), otherOwner));
        assertNotFound(() -> getAnalysisStatusService.getStatus(analysis.id(), otherOwner));

        jdbcTemplate.update("UPDATE invoward.analyses SET expires_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(1)), analysis.id());
        assertNotFound(() -> getAnalysisService.get(analysis.id(), owner));
        assertNotFound(() -> getAnalysisStatusService.getStatus(analysis.id(), owner));
    }

    private Analysis persistAnalysis(
            io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner owner, Instant now) {
        Analysis analysis = Analysis.create(
                UUID.randomUUID(), owner, PriceTolerance.exactMatch(), now);
        analysisRepository.create(analysis);
        analysisJobRepository.create(AnalysisJob.waitingForUser(
                UUID.randomUUID(), analysis.id(), now));
        return analysis;
    }

    private UUID insertUser() throws Exception {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
    }

    private UUID insertGuestSession() throws Exception {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertGuestSession);
    }

    private static void assertNotFound(org.junit.jupiter.api.function.Executable executable) {
        AnalysisNotFoundException exception = assertThrows(
                AnalysisNotFoundException.class, executable);
        assertEquals("Analysis was not found.", exception.getMessage());
    }
}
