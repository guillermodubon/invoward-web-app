package io.github.guillermodubon.invoward.identity.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Framework-independent identity account model. */
public record UserAccount(
        UUID id,
        String displayName,
        String email,
        String passwordHash,
        boolean emailVerified,
        UserStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static final int MAX_EMAIL_CODE_POINTS = 320;
    public static final int MAX_DISPLAY_NAME_CODE_POINTS = 120;

    public UserAccount {
        Objects.requireNonNull(id, "id must not be null");
        displayName = normalizeDisplayName(displayName);
        email = normalizeEmail(email);
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("passwordHash must not be blank");
        }
        Objects.requireNonNull(status, "status must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /** Trims surrounding Unicode whitespace and lowercases with locale-independent rules. */
    public static String normalizeEmail(String email) {
        Objects.requireNonNull(email, "email must not be null");
        String normalizedEmail = UnicodeWhitespace.strip(email).toLowerCase(Locale.ROOT);
        if (normalizedEmail.isEmpty()) {
            throw new IllegalArgumentException("Email must not be blank");
        }
        if (normalizedEmail.codePointCount(0, normalizedEmail.length()) > MAX_EMAIL_CODE_POINTS) {
            throw new IllegalArgumentException("Email must not exceed 320 Unicode code points");
        }
        return normalizedEmail;
    }

    /** Trims surrounding Unicode whitespace while preserving internal whitespace and Unicode. */
    public static String normalizeDisplayName(String displayName) {
        Objects.requireNonNull(displayName, "displayName must not be null");
        String normalizedDisplayName = UnicodeWhitespace.strip(displayName);
        int codePointCount = normalizedDisplayName.codePointCount(0, normalizedDisplayName.length());
        if (codePointCount == 0) {
            throw new IllegalArgumentException("Display name must not be blank");
        }
        if (codePointCount > MAX_DISPLAY_NAME_CODE_POINTS) {
            throw new IllegalArgumentException("Display name must not exceed 120 Unicode code points");
        }
        return normalizedDisplayName;
    }

    /**
     * Applies the registration-verification transition while preserving this persistence version.
     * The repository returns the incremented version after the change is flushed.
     */
    public UserAccount activateVerifiedRegistration(Instant activatedAt) {
        Objects.requireNonNull(activatedAt, "activatedAt must not be null");
        if (status == UserStatus.ACTIVE && emailVerified) {
            return this;
        }
        if (status != UserStatus.PENDING_VERIFICATION || emailVerified) {
            throw new IllegalStateException("Only a pending unverified account can be activated");
        }
        return new UserAccount(
                id, displayName, email, passwordHash, true, UserStatus.ACTIVE,
                version, createdAt, activatedAt);
    }

    public UserAccount updateDisplayName(String newDisplayName, Instant changedAt) {
        requireActiveVerifiedAccount();
        return new UserAccount(
                id, normalizeDisplayName(newDisplayName), email, passwordHash,
                emailVerified, status, version, createdAt, requireTimestamp(changedAt));
    }

    public UserAccount updatePasswordHash(String newPasswordHash, Instant changedAt) {
        requireActiveVerifiedAccount();
        return new UserAccount(
                id, displayName, email, newPasswordHash, emailVerified, status,
                version, createdAt, requireTimestamp(changedAt));
    }

    public UserAccount updateEmail(String newEmail, Instant changedAt) {
        requireActiveVerifiedAccount();
        return new UserAccount(
                id, displayName, normalizeEmail(newEmail), passwordHash, true, status,
                version, createdAt, requireTimestamp(changedAt));
    }

    private void requireActiveVerifiedAccount() {
        if (status != UserStatus.ACTIVE || !emailVerified) {
            throw new IllegalStateException("Account must be active and verified for this change");
        }
    }

    private static Instant requireTimestamp(Instant timestamp) {
        return Objects.requireNonNull(timestamp, "changedAt must not be null");
    }

    @Override
    public String toString() {
        return "UserAccount[id=" + id + ", passwordHash=[REDACTED]]";
    }
}
