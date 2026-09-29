package io.github.guillermodubon.invoward.identity.infrastructure.security;

import tools.jackson.databind.ObjectMapper;
import io.github.guillermodubon.invoward.identity.api.model.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Component
public final class JsonAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonAuthenticationFailureHandler.class);
    private static final String INVALID_REQUEST_MESSAGE = "The request could not be processed.";
    private static final String AUTHENTICATION_FAILED_MESSAGE = "Invalid email or password.";

    private final ObjectMapper objectMapper;

    public JsonAuthenticationFailureHandler(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        ApiErrorResponse body;
        if (exception instanceof InvalidLoginRequestException) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            body = new ApiErrorResponse("INVALID_REQUEST", INVALID_REQUEST_MESSAGE);
        } else {
            LOGGER.warn("operation=login result=failure");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            body = new ApiErrorResponse("AUTHENTICATION_FAILED", AUTHENTICATION_FAILED_MESSAGE);
        }

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
