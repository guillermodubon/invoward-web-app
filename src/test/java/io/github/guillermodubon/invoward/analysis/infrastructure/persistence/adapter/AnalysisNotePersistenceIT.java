package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisNoteRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.application.exception.NoteVersionConflictException;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisNote;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "invoward.email.provider=disabled")
@Transactional
class AnalysisNotePersistenceIT {

    private static final Instant CREATED_AT = Instant.parse("2026-09-24T14:00:00Z");

    @Autowired
    private AnalysisNoteRepository noteRepository;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void createsReadsUpdatesAndDeletesNoteWithoutChangingAnalysis() throws Exception {
        RegisteredUserOwner owner = new RegisteredUserOwner(insertUser());
        Analysis analysis = analysisRepository.create(Analysis.create(
                UUID.randomUUID(), owner, PriceTolerance.exactMatch(), CREATED_AT));
        AnalysisNote initial = AnalysisNote.create(analysis.id(), "Supplier confirmed the fee.", CREATED_AT);

        AnalysisNote created = noteRepository.create(initial);

        assertEquals(initial.analysisId(), created.analysisId());
        assertEquals(initial.text(), created.text());
        assertEquals(0, created.version());
        assertEquals(CREATED_AT, created.createdAt());
        assertEquals(CREATED_AT, created.updatedAt());
        assertEquals(created, noteRepository.findByAnalysisId(analysis.id()).orElseThrow());

        Instant modifiedAt = CREATED_AT.plusSeconds(5);
        AnalysisNote updated = noteRepository.update(created.replaceText("Fee approved.", modifiedAt));
        assertEquals("Fee approved.", updated.text());
        assertEquals(1, updated.version());
        assertEquals(CREATED_AT, updated.createdAt());
        assertEquals(modifiedAt, updated.updatedAt());
        assertEquals(updated, noteRepository.findByAnalysisId(analysis.id()).orElseThrow());

        AnalysisNote noOp = noteRepository.update(updated.replaceText("Fee approved.", modifiedAt.plusSeconds(5)));
        assertEquals(1, noOp.version());
        assertEquals(modifiedAt, noOp.updatedAt());

        noteRepository.delete(analysis.id(), noOp.version());
        assertTrue(noteRepository.findByAnalysisId(analysis.id()).isEmpty());

        Analysis unchanged = analysisRepository.findOwnedById(analysis.id(), owner, modifiedAt.plusSeconds(10))
                .orElseThrow();
        assertEquals(analysis, unchanged);
        assertEquals(AnalysisStatus.CREATED, unchanged.status());
    }

    @Test
    void uniqueConstraintAllowsOnlyOneNotePerAnalysis() throws Exception {
        Analysis analysis = createRegisteredAnalysis();
        noteRepository.create(AnalysisNote.create(analysis.id(), "First note", CREATED_AT));

        assertThrows(NoteVersionConflictException.class,
                () -> noteRepository.create(AnalysisNote.create(analysis.id(), "Second note", CREATED_AT)));
    }

    @Test
    void persistenceVersionRejectsStaleUpdatesAndDeletes() throws Exception {
        Analysis analysis = createRegisteredAnalysis();
        AnalysisNote created = noteRepository.create(AnalysisNote.create(analysis.id(), "Initial", CREATED_AT));
        AnalysisNote stale = created.replaceText("Stale update", CREATED_AT.plusSeconds(1));
        AnalysisNote current = noteRepository.update(created.replaceText("Current update", CREATED_AT.plusSeconds(2)));

        assertEquals(1, current.version());
        assertThrows(NoteVersionConflictException.class, () -> noteRepository.update(stale));
        assertThrows(NoteVersionConflictException.class,
                () -> noteRepository.delete(analysis.id(), created.version()));
        assertEquals(current, noteRepository.findByAnalysisId(analysis.id()).orElseThrow());
    }

    @Test
    void unrelatedForeignKeyViolationIsNotTranslatedAsNoteVersionConflict() {
        assertThrows(DataIntegrityViolationException.class,
                () -> noteRepository.create(AnalysisNote.create(UUID.randomUUID(), "No parent", CREATED_AT)));
    }

    @Test
    void rejectsNoteTimestampMovingBackwards() throws Exception {
        Analysis analysis = createRegisteredAnalysis();
        AnalysisNote created = noteRepository.create(AnalysisNote.create(analysis.id(), "Initial", CREATED_AT));

        assertThrows(IllegalArgumentException.class,
                () -> noteRepository.update(created.replaceText("Changed", CREATED_AT.minusNanos(1))));
        assertFalse(noteRepository.findByAnalysisId(analysis.id()).isEmpty());
    }

    private Analysis createRegisteredAnalysis() throws Exception {
        RegisteredUserOwner owner = new RegisteredUserOwner(insertUser());
        return analysisRepository.create(Analysis.create(
                UUID.randomUUID(), owner, PriceTolerance.exactMatch(), CREATED_AT));
    }

    private UUID insertUser() throws Exception {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
    }
}
