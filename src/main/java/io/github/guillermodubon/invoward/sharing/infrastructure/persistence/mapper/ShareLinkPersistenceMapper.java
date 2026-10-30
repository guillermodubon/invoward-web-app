package io.github.guillermodubon.invoward.sharing.infrastructure.persistence.mapper;

import io.github.guillermodubon.invoward.sharing.domain.ShareLink;
import io.github.guillermodubon.invoward.sharing.infrastructure.persistence.entity.ShareLinkJpaEntity;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Maps provider-neutral share-link state to and from its JPA representation. */
@Component
public class ShareLinkPersistenceMapper {

    public ShareLinkJpaEntity toEntity(ShareLink shareLink) {
        Objects.requireNonNull(shareLink, "shareLink must not be null");
        ShareLinkJpaEntity entity = ShareLinkJpaEntity.create(shareLink.id());
        setFields(shareLink, entity);
        return entity;
    }

    public ShareLink toDomain(ShareLinkJpaEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new ShareLink(
                entity.getId(),
                entity.getAnalysisId(),
                entity.getTokenHash(),
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                entity.getRevokedAt());
    }

    public void updateEntity(ShareLink shareLink, ShareLinkJpaEntity entity) {
        Objects.requireNonNull(shareLink, "shareLink must not be null");
        Objects.requireNonNull(entity, "entity must not be null");
        if (!shareLink.id().equals(entity.getId())
                || !shareLink.analysisId().equals(entity.getAnalysisId())
                || !shareLink.tokenHash().equals(entity.getTokenHash())
                || !shareLink.createdAt().equals(entity.getCreatedAt())
                || !shareLink.expiresAt().equals(entity.getExpiresAt())) {
            throw new IllegalArgumentException("Share-link identity does not match persistence entity");
        }
        entity.setRevokedAt(shareLink.revokedAt());
    }

    private static void setFields(ShareLink shareLink, ShareLinkJpaEntity entity) {
        entity.setAnalysisId(shareLink.analysisId());
        entity.setTokenHash(shareLink.tokenHash());
        entity.setCreatedAt(shareLink.createdAt());
        entity.setExpiresAt(shareLink.expiresAt());
        entity.setRevokedAt(shareLink.revokedAt());
    }
}
