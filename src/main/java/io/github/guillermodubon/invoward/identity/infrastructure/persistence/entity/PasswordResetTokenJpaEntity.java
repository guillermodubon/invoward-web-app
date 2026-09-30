package io.github.guillermodubon.invoward.identity.infrastructure.persistence.entity;

import io.github.guillermodubon.invoward.identity.application.model.PasswordResetToken;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(schema = "invoward", name = "password_reset_tokens")
public class PasswordResetTokenJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static PasswordResetTokenJpaEntity fromModel(PasswordResetToken token) {
        PasswordResetToken source = Objects.requireNonNull(token, "token must not be null");
        PasswordResetTokenJpaEntity entity = new PasswordResetTokenJpaEntity();
        entity.userId = source.userId();
        entity.tokenHash = source.tokenHash();
        entity.expiresAt = source.expiresAt();
        entity.usedAt = source.usedAt();
        entity.createdAt = source.createdAt();
        return entity;
    }

    public PasswordResetToken toModel() {
        return new PasswordResetToken(userId, tokenHash, expiresAt, usedAt, createdAt);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getCreatedAt() { return createdAt; }

    protected PasswordResetTokenJpaEntity() {
    }
}
