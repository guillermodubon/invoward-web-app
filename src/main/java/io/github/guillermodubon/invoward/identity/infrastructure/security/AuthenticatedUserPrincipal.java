package io.github.guillermodubon.invoward.identity.infrastructure.security;

import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable Spring Security projection of an identity account. */
public final class AuthenticatedUserPrincipal implements UserDetails, AuthenticatedIdentity {

    private final UUID userId;
    private final String displayName;
    private final String email;
    private final String passwordHash;
    private final boolean emailVerified;
    private final UserStatus status;

    public AuthenticatedUserPrincipal(UserAccount account) {
        Objects.requireNonNull(account, "account must not be null");
        this.userId = account.id();
        this.displayName = account.displayName();
        this.email = account.email();
        this.passwordHash = account.passwordHash();
        this.emailVerified = account.emailVerified();
        this.status = account.status();
    }

    public UUID userId() {
        return userId;
    }

    public String displayName() {
        return displayName;
    }

    public String email() {
        return email;
    }

    public boolean emailVerified() {
        return emailVerified;
    }

    public UserStatus status() {
        return status;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return status == UserStatus.ACTIVE && emailVerified;
    }

    @Override
    public String toString() {
        return "AuthenticatedUserPrincipal[userId=" + userId + ", credentials=[REDACTED]]";
    }
}
