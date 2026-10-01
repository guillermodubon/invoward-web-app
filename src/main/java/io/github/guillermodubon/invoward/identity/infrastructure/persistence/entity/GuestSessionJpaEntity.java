package io.github.guillermodubon.invoward.identity.infrastructure.persistence.entity;

import io.github.guillermodubon.invoward.identity.application.model.GuestSession;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(schema = "invoward", name = "guest_sessions")
public class GuestSessionJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static GuestSessionJpaEntity fromModel(GuestSession guestSession) {
        GuestSession source = Objects.requireNonNull(guestSession, "guestSession must not be null");
        GuestSessionJpaEntity entity = new GuestSessionJpaEntity();
        entity.id = source.id();
        entity.expiresAt = source.expiresAt();
        entity.lastSeenAt = source.lastSeenAt();
        entity.createdAt = source.createdAt();
        return entity;
    }

    public GuestSession toModel() {
        return new GuestSession(id, expiresAt, lastSeenAt, createdAt);
    }

    public UUID getId() { return id; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    protected GuestSessionJpaEntity() {
    }
}
