package io.github.guillermodubon.invoward.identity.infrastructure.security;

import tools.jackson.databind.ObjectMapper;
import io.github.guillermodubon.invoward.identity.api.model.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Component
public final class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private static final ApiErrorResponse ACCESS_DENIED = new ApiErrorResponse(
            "ACCESS_DENIED",
            "Access is denied.");
    private static final ApiErrorResponse CSRF_INVALID = new ApiErrorResponse(
            "CSRF_INVALID",
            "The request could not be validated.");

    private final ObjectMapper objectMapper;

    public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                exception instanceof CsrfException ? CSRF_INVALID : ACCESS_DENIED);
    }
}
