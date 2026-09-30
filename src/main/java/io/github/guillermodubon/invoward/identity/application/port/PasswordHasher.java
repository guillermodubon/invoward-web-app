package io.github.guillermodubon.invoward.identity.application.port;

/** Application-facing contract for password hashing and verification. */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String encodedPassword);
}
