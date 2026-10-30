package io.github.guillermodubon.invoward.sharing.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.sharing.application.port.ShareLinkRepository;
import io.github.guillermodubon.invoward.sharing.domain.ShareLink;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "invoward.email.provider=disabled")
class RevokeShareLinkIT {

    private final List<UUID> analysisIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();

    @Autowired
    private RevokeShareLinkService revokeShareLinkService;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private ShareLinkRepository shareLinkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void cleanFixtures() {
        analysisIds.forEach(id -> jdbcTemplate.update("DELETE FROM invoward.analyses WHERE id = ?", id));
        userIds.forEach(id -> jdbcTemplate.update("DELETE FROM invoward.users WHERE id = ?", id));
    }

    @Test
    void concurrentRevocationsAreIdempotentAndKeepTheFirstTimestamp() throws Exception {
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        userIds.add(userId);
        AnalysisOwner owner = new RegisteredUserOwner(userId);
        Instant createdAt = clock.instant().minusSeconds(60);
        Analysis analysis = analysisRepository.create(
                Analysis.create(UUID.randomUUID(), owner, PriceTolerance.exactMatch(), createdAt));
        analysisIds.add(analysis.id());
        ShareLink createdLink = shareLinkRepository.create(new ShareLink(
                UUID.randomUUID(), analysis.id(), "e".repeat(64), createdAt,
                clock.instant().plusSeconds(3600), null));
        ShareLink persistedLink = shareLinkRepository
                .findByAnalysisIdAndId(analysis.id(), createdLink.id())
                .orElseThrow();
        Instant persistedExpiry = readPersistedExpiry(persistedLink.id());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> revokeAtBarrier(
                    analysis.id(), owner, persistedLink.id(), ready, start));
            Future<?> second = executor.submit(() -> revokeAtBarrier(
                    analysis.id(), owner, persistedLink.id(), ready, start));

            assertTrue(ready.await(10, TimeUnit.SECONDS), "both revocations should reach the start barrier");
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);

            Instant firstRevokedAt = shareLinkRepository
                    .findByAnalysisIdAndId(analysis.id(), persistedLink.id()).orElseThrow().revokedAt();
            assertNotNull(firstRevokedAt);

            revokeShareLinkService.revoke(analysis.id(), owner, persistedLink.id());

            ShareLink afterRepeatedRevocation = shareLinkRepository
                    .findByAnalysisIdAndId(analysis.id(), persistedLink.id()).orElseThrow();
            assertEquals(firstRevokedAt, afterRepeatedRevocation.revokedAt());
            assertEquals(persistedLink.tokenHash(), afterRepeatedRevocation.tokenHash());
            assertEquals(persistedExpiry, readPersistedExpiry(persistedLink.id()));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void revokeAtBarrier(
            UUID analysisId,
            AnalysisOwner owner,
            UUID shareLinkId,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("share-link revoke test start signal timed out");
            }
            revokeShareLinkService.revoke(analysisId, owner, shareLinkId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("share-link revoke test was interrupted", exception);
        }
    }

    private Instant readPersistedExpiry(UUID shareLinkId) {
        return jdbcTemplate.queryForObject(
                "SELECT expires_at FROM invoward.share_links WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getTimestamp("expires_at").toInstant(),
                shareLinkId);
    }
}
