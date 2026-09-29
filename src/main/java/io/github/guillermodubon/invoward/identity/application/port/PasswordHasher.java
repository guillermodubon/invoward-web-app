package io.github.guillermodubon.invoward.identity.application.port;

/** Application-facing contract for one-way password hashing. */
public interface PasswordHasher {

    String hash(String rawPassword);
}
