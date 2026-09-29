package io.github.guillermodubon.invoward.identity.infrastructure.persistence.entity;

import io.github.guillermodubon.invoward.identity.domain.EmailVerificationPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(schema = "invoward", name = "email_verification_tokens")
public class EmailVerificationTokenJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private EmailVerificationPurpose purpose;

    @Column(name = "target_email", nullable = false, length = 320)
    private String targetEmail;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static EmailVerificationTokenJpaEntity createRegistration(
            UUID userId,
            String normalizedTargetEmail,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt) {
        EmailVerificationTokenJpaEntity entity = new EmailVerificationTokenJpaEntity();
        entity.userId = Objects.requireNonNull(userId, "userId must not be null");
        entity.targetEmail = Objects.requireNonNull(normalizedTargetEmail,
                "normalizedTargetEmail must not be null");
        entity.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        entity.purpose = EmailVerificationPurpose.REGISTRATION;
        entity.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        entity.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        return entity;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public EmailVerificationPurpose getPurpose() { return purpose; }
    public String getTargetEmail() { return targetEmail; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getCreatedAt() { return createdAt; }

    protected EmailVerificationTokenJpaEntity() {
    }
}
