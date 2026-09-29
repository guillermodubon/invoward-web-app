package io.github.guillermodubon.invoward.identity.infrastructure.crypto;

import io.github.guillermodubon.invoward.identity.application.port.PasswordHasher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Objects;

/** Adapts Spring Security's configured password encoder to the identity application port. */
public final class SpringSecurityPasswordHasher implements PasswordHasher {

    private final PasswordEncoder passwordEncoder;

    public SpringSecurityPasswordHasher(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
    }

    @Override
    public String hash(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
}
