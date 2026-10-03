package io.github.guillermodubon.invoward.document.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.document.application.exception.DocumentRoleAlreadyExistsException;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.infrastructure.persistence.mapper.DocumentPersistenceMapper;
import io.github.guillermodubon.invoward.document.infrastructure.persistence.repository.SpringDataDocumentJpaRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaDocumentRepositoryTest {

    private final SpringDataDocumentJpaRepository springRepository =
            mock(SpringDataDocumentJpaRepository.class);
    private final JpaDocumentRepository repository = new JpaDocumentRepository(
            springRepository, new DocumentPersistenceMapper());

    @Test
    void mapsOnlyPartialUniqueRoleIndexViolationsToSafeConflict() {
        ConstraintViolationException databaseFailure = new ConstraintViolationException(
                "duplicate role", new SQLException("duplicate role"),
                "documents_one_reference_per_analysis_uq");
        when(springRepository.saveAndFlush(any())).thenThrow(
                new DataIntegrityViolationException("constraint violation", databaseFailure));

        assertThrows(DocumentRoleAlreadyExistsException.class,
                () -> repository.create(document(DocumentRole.REFERENCE)));
    }

    @Test
    void preservesUnrelatedDatabaseConstraintFailures() {
        DataIntegrityViolationException databaseFailure = new DataIntegrityViolationException(
                "constraint violation",
                new ConstraintViolationException(
                        "duplicate storage key", new SQLException("duplicate storage key"),
                        "documents_storage_key_uq"));
        when(springRepository.saveAndFlush(any())).thenThrow(databaseFailure);

        DataIntegrityViolationException actual = assertThrows(DataIntegrityViolationException.class,
                () -> repository.create(document(DocumentRole.REFERENCE)));

        assertSame(databaseFailure, actual);
    }

    private static Document document(DocumentRole role) {
        return Document.createUploaded(UUID.randomUUID(), UUID.randomUUID(), role,
                "document.pdf", "application/pdf", 100, 1, "a".repeat(64),
                "test/" + UUID.randomUUID(), null, Instant.parse("2026-10-02T00:00:00Z"));
    }
}
