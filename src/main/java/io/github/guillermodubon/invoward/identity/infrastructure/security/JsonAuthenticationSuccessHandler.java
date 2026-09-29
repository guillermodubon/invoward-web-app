package io.github.guillermodubon.invoward.identity.infrastructure.security;

import tools.jackson.databind.ObjectMapper;
import io.github.guillermodubon.invoward.identity.api.model.CurrentUserResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Component
public final class JsonAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonAuthenticationSuccessHandler.class);

    private final ObjectMapper objectMapper;

    public JsonAuthenticationSuccessHandler(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        if (!(authentication.getPrincipal() instanceof AuthenticatedUserPrincipal principal)) {
            throw new ServletException("Unsupported authenticated principal");
        }

        CurrentUserResponse body = CurrentUserResponse.from(principal);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), body);
        LOGGER.info("operation=login result=success userId={}", principal.userId());
    }
}
