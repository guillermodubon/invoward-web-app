package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.NoteVersionConflictException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisNoteReadModel;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisNoteRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisNote;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "invoward.email.provider=disabled")
class PutAnalysisNoteServiceIT {

    private static final Instant CREATED_AT = Instant.parse("2026-10-20T12:00:00Z");

    @Autowired
    private PutAnalysisNoteService putAnalysisNoteService;

    @Autowired
    private GetAnalysisNoteService getAnalysisNoteService;

    @Autowired
    private AnalysisNoteRepository noteRepository;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void concurrentFirstWritesProduceExactlyOneNoteAndOneVersionConflict() throws Exception {
        AnalysisContext context = createRegisteredAnalysis();
        try {
            int successes = runTwoConcurrentWrites(context, null);

            assertEquals(1, successes);
            AnalysisNote stored = noteRepository.findByAnalysisId(context.analysisId()).orElseThrow();
            assertTrue(stored.text().startsWith("Concurrent note "));
            assertEquals(0, stored.version());
        } finally {
            deleteAnalysis(context);
        }
    }

    @Test
    void concurrentUpdatesWithSameExpectedVersionProduceOneWinner() throws Exception {
        AnalysisContext context = createRegisteredAnalysis();
        try {
            putAnalysisNoteService.put(context.analysisId(), context.userId(), "Initial", null);

            int successes = runTwoConcurrentWrites(context, 0L);

            assertEquals(1, successes);
            AnalysisNote stored = noteRepository.findByAnalysisId(context.analysisId()).orElseThrow();
            assertTrue(stored.text().startsWith("Concurrent note "));
            assertEquals(1, stored.version());
        } finally {
            deleteAnalysis(context);
        }
    }

    @Test
    void staleReplacementAndClearConflictUntilFreshVersionIsRead() throws Exception {
        AnalysisContext context = createRegisteredAnalysis();
        try {
            AnalysisNoteReadModel created = putAnalysisNoteService.put(
                    context.analysisId(), context.userId(), "Initial", null);
            AnalysisNoteReadModel updated = putAnalysisNoteService.put(
                    context.analysisId(), context.userId(), "Winning edit", created.version());

            assertEquals(1L, updated.version());
            assertThrows(NoteVersionConflictException.class, () -> putAnalysisNoteService.put(
                    context.analysisId(), context.userId(), "Stale edit", created.version()));
            assertThrows(NoteVersionConflictException.class, () -> putAnalysisNoteService.put(
                    context.analysisId(), context.userId(), "  \n ", created.version()));

            AnalysisNoteReadModel reread = getAnalysisNoteService.get(
                    context.analysisId(), context.userId());
            assertEquals(updated, reread);

            AnalysisNoteReadModel subsequent = putAnalysisNoteService.put(
                    context.analysisId(), context.userId(), "Follow-up edit", reread.version());
            assertEquals("Follow-up edit", subsequent.text());
            assertEquals(2L, subsequent.version());
            assertEquals(subsequent, getAnalysisNoteService.get(context.analysisId(), context.userId()));
        } finally {
            deleteAnalysis(context);
        }
    }

    @Test
    void noteWriteParticipatesInCallerTransactionAndRollsBackAtomically() throws Exception {
        AnalysisContext context = createRegisteredAnalysis();
        try {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);

            assertThrows(ForcedRollback.class, () -> transaction.executeWithoutResult(status -> {
                putAnalysisNoteService.put(context.analysisId(), context.userId(), "Rolled back", null);
                throw new ForcedRollback();
            }));

            assertTrue(noteRepository.findByAnalysisId(context.analysisId()).isEmpty());
        } finally {
            deleteAnalysis(context);
        }
    }

    private int runTwoConcurrentWrites(AnalysisContext context, Long expectedVersion) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = submitWrite(executor, ready, start, context, expectedVersion, "A");
            Future<Boolean> second = submitWrite(executor, ready, start, context, expectedVersion, "B");
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Concurrent writers did not become ready");
            start.countDown();
            return (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Future<Boolean> submitWrite(
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch start,
            AnalysisContext context,
            Long expectedVersion,
            String suffix) {
        return executor.submit(() -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent writer start was not released");
            }
            try {
                putAnalysisNoteService.put(context.analysisId(), context.userId(),
                        "Concurrent note " + suffix, expectedVersion);
                return true;
            } catch (NoteVersionConflictException expected) {
                return false;
            }
        });
    }

    private AnalysisContext createRegisteredAnalysis() throws Exception {
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        UUID analysisId = UUID.randomUUID();
        analysisRepository.create(Analysis.create(analysisId, new RegisteredUserOwner(userId),
                PriceTolerance.exactMatch(), CREATED_AT));
        return new AnalysisContext(analysisId, userId);
    }

    private void deleteAnalysis(AnalysisContext context) {
        jdbcTemplate.update("DELETE FROM invoward.analyses WHERE id = ?", context.analysisId());
        jdbcTemplate.update("DELETE FROM invoward.users WHERE id = ?", context.userId());
    }

    private record AnalysisContext(UUID analysisId, UUID userId) { }

    private static final class ForcedRollback extends RuntimeException { }
}
