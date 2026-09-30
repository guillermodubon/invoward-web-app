package io.github.guillermodubon.invoward.identity.infrastructure.persistence.entity;

import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(schema = "invoward", name = "users")
public class UserJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private UserStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static UserJpaEntity createPendingRegistration(
            String displayName,
            String email,
            String passwordHash,
            Instant createdAt) {
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        UserJpaEntity entity = new UserJpaEntity();
        entity.displayName = displayName;
        entity.email = email;
        entity.passwordHash = passwordHash;
        entity.emailVerified = false;
        entity.status = UserStatus.PENDING_VERIFICATION;
        entity.version = 0;
        entity.createdAt = createdAt;
        entity.updatedAt = createdAt;
        return entity;
    }

    public void activateVerifiedRegistration(Instant activatedAt) {
        Objects.requireNonNull(activatedAt, "activatedAt must not be null");
        if (status == UserStatus.ACTIVE && emailVerified) {
            return;
        }
        if (status != UserStatus.PENDING_VERIFICATION || emailVerified) {
            throw new IllegalStateException("Only a pending unverified account can be activated");
        }
        status = UserStatus.ACTIVE;
        emailVerified = true;
        updatedAt = activatedAt;
    }

    public void updateDisplayName(String newDisplayName, Instant changedAt) {
        displayName = UserAccount.normalizeDisplayName(newDisplayName);
        updatedAt = Objects.requireNonNull(changedAt, "changedAt must not be null");
    }

    public void updatePasswordHash(String newPasswordHash, Instant changedAt) {
        if (newPasswordHash == null || newPasswordHash.isBlank()) {
            throw new IllegalArgumentException("passwordHash must not be blank");
        }
        passwordHash = newPasswordHash;
        updatedAt = Objects.requireNonNull(changedAt, "changedAt must not be null");
    }

    public void updateEmail(String newEmail, Instant changedAt) {
        email = UserAccount.normalizeEmail(newEmail);
        emailVerified = true;
        updatedAt = Objects.requireNonNull(changedAt, "changedAt must not be null");
    }

    public UUID getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isEmailVerified() { return emailVerified; }
    public UserStatus getStatus() { return status; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    protected UserJpaEntity() {
    }
}
