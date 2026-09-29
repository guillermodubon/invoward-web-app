package io.github.guillermodubon.invoward.identity.infrastructure.security;

import org.springframework.security.core.AuthenticationException;

final class InvalidLoginRequestException extends AuthenticationException {

    InvalidLoginRequestException() {
        super("Invalid login request");
    }
}
