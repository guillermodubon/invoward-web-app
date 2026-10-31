package io.github.guillermodubon.invoward.sharing.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkPage;
import io.github.guillermodubon.invoward.sharing.application.exception.DuplicateShareTokenHashException;
import io.github.guillermodubon.invoward.sharing.application.port.ShareLinkRepository;
import io.github.guillermodubon.invoward.sharing.domain.ShareLink;
import io.github.guillermodubon.invoward.sharing.infrastructure.persistence.entity.ShareLinkJpaEntity;
import io.github.guillermodubon.invoward.sharing.infrastructure.persistence.mapper.ShareLinkPersistenceMapper;
import io.github.guillermodubon.invoward.sharing.infrastructure.persistence.repository.SpringDataShareLinkJpaRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL/JPA adapter for share-link metadata. */
@Repository
@Transactional
public class JpaShareLinkRepository implements ShareLinkRepository {

    private static final String TOKEN_HASH_UNIQUE_CONSTRAINT = "share_links_token_hash_uq";

    private static final Sort PAGE_ORDER = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id"));

    private final SpringDataShareLinkJpaRepository repository;
    private final ShareLinkPersistenceMapper mapper;

    public JpaShareLinkRepository(
            SpringDataShareLinkJpaRepository repository,
            ShareLinkPersistenceMapper mapper) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public ShareLink create(ShareLink shareLink) {
        Objects.requireNonNull(shareLink, "shareLink must not be null");
        try {
            return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(shareLink)));
        } catch (DataIntegrityViolationException exception) {
            if (isTokenHashCollision(exception)) {
                throw new DuplicateShareTokenHashException();
            }
            throw exception;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShareLink> findByTokenHash(String tokenHash) {
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        return repository.findByTokenHash(tokenHash).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShareLink> findByAnalysisIdAndId(UUID analysisId, UUID shareLinkId) {
        requireIdentity(analysisId, shareLinkId);
        return repository.findByAnalysisIdAndId(analysisId, shareLinkId).map(mapper::toDomain);
    }

    @Override
    public Optional<ShareLink> findByAnalysisIdAndIdForUpdate(UUID analysisId, UUID shareLinkId) {
        requireIdentity(analysisId, shareLinkId);
        return repository.findByAnalysisIdAndIdForUpdate(analysisId, shareLinkId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveByAnalysisId(UUID analysisId, Instant now) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return repository.countByAnalysisIdAndRevokedAtIsNullAndExpiresAtAfter(analysisId, now);
    }

    @Override
    @Transactional(readOnly = true)
    public ShareLinkPage findPageByAnalysisId(UUID analysisId, int page, int size) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        if (page < 0 || page > ShareLinkPage.MAX_PAGE || size < 1 || size > ShareLinkPage.MAX_SIZE) {
            throw new IllegalArgumentException("Share-link page bounds are invalid");
        }
        Page<ShareLinkJpaEntity> persistedPage = repository.findByAnalysisId(
                analysisId, PageRequest.of(page, size, PAGE_ORDER));
        return new ShareLinkPage(
                persistedPage.getContent().stream().map(mapper::toDomain).toList(),
                persistedPage.getNumber(),
                persistedPage.getSize(),
                persistedPage.getTotalElements());
    }

    @Override
    public Optional<ShareLink> update(ShareLink shareLink) {
        Objects.requireNonNull(shareLink, "shareLink must not be null");
        return repository.findByAnalysisIdAndId(shareLink.analysisId(), shareLink.id())
                .map(entity -> {
                    mapper.updateEntity(shareLink, entity);
                    repository.flush();
                    return mapper.toDomain(entity);
                });
    }

    private static void requireIdentity(UUID analysisId, UUID shareLinkId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(shareLinkId, "shareLinkId must not be null");
    }

    private static boolean isTokenHashCollision(Throwable failure) {
        for (Throwable cause = failure; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                return TOKEN_HASH_UNIQUE_CONSTRAINT.equals(constraintViolation.getConstraintName());
            }
        }
        return false;
    }
}
