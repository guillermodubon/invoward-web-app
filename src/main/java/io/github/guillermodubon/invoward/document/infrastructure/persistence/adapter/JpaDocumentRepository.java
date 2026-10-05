package io.github.guillermodubon.invoward.document.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.application.exception.DocumentRoleAlreadyExistsException;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.infrastructure.persistence.mapper.DocumentPersistenceMapper;
import io.github.guillermodubon.invoward.document.infrastructure.persistence.repository.SpringDataDocumentJpaRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL-backed Document repository adapter. */
@Repository
@Transactional
public class JpaDocumentRepository implements DocumentRepository {

    private final SpringDataDocumentJpaRepository repository;
    private final DocumentPersistenceMapper mapper;

    public JpaDocumentRepository(
            SpringDataDocumentJpaRepository repository,
            DocumentPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Document create(Document document) {
        Objects.requireNonNull(document, "document must not be null");
        try {
            return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(document)));
        } catch (DataIntegrityViolationException exception) {
            if (isRoleConstraintViolation(exception)) {
                throw new DocumentRoleAlreadyExistsException();
            }
            throw exception;
        }
    }

    @Override
    public Optional<Document> updateTypes(Document document) {
        Objects.requireNonNull(document, "document must not be null");
        return repository.findByIdAndAnalysisId(document.id(), document.analysisId())
                .map(entity -> {
                    mapper.updateTypes(document, entity);
                    repository.flush();
                    return mapper.toDomain(entity);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public List<Document> findByAnalysisId(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        return repository.findByAnalysisId(analysisId).stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Document> findByIdAndAnalysisId(UUID documentId, UUID analysisId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        return repository.findByIdAndAnalysisId(documentId, analysisId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByAnalysisIdAndRole(UUID analysisId, DocumentRole role) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        return repository.existsByAnalysisIdAndRole(analysisId, role);
    }

    @Override
    @Transactional(readOnly = true)
    public long sumSizeBytesByAnalysisId(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        return repository.sumSizeBytesByAnalysisId(analysisId);
    }

    private static boolean isRoleConstraintViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                String constraintName = constraintViolation.getConstraintName();
                return "documents_one_reference_per_analysis_uq".equals(constraintName)
                        || "documents_one_invoice_per_analysis_uq".equals(constraintName);
            }
        }
        return false;
    }
}
