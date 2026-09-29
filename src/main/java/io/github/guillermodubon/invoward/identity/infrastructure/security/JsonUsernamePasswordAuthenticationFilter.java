package io.github.guillermodubon.invoward.identity.infrastructure.security;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.http.InvalidMediaTypeException;

import java.util.Objects;

/** Parses and delegates bounded JSON login requests to Spring Security authentication. */
public final class JsonUsernamePasswordAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

    static final int MAX_REQUEST_BODY_BYTES = 8 * 1024;
    private static final int MAX_REQUEST_BODY_BYTES_WITH_SENTINEL = MAX_REQUEST_BODY_BYTES + 1;
    private static final int MAX_PASSWORD_CODE_POINTS = 128;
    private static final String LOGIN_PATH = "/api/auth/login";

    private final ObjectMapper objectMapper;

    public JsonUsernamePasswordAuthenticationFilter(
            AuthenticationManager authenticationManager,
            ObjectMapper objectMapper,
            AuthenticationSuccessHandler successHandler,
            AuthenticationFailureHandler failureHandler) {
        super(loginRequestMatcher(), Objects.requireNonNull(authenticationManager));
        this.objectMapper = Objects.requireNonNull(objectMapper);
        setAuthenticationSuccessHandler(Objects.requireNonNull(successHandler));
        setAuthenticationFailureHandler(Objects.requireNonNull(failureHandler));
    }

    @Override
    public Authentication attemptAuthentication(
            HttpServletRequest request,
            HttpServletResponse response) {
        requireJsonContentType(request.getContentType());
        byte[] body = readBoundedBody(request);
        JsonNode payload = parsePayload(body);
        String normalizedEmail = normalizedEmail(payload);
        String rawPassword = rawPassword(payload);

        return getAuthenticationManager().authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(normalizedEmail, rawPassword));
    }

    private static RequestMatcher loginRequestMatcher() {
        return request -> "POST".equals(request.getMethod())
                && (LOGIN_PATH.equals(request.getServletPath())
                        || (request.getContextPath() + LOGIN_PATH).equals(request.getRequestURI()));
    }

    private static void requireJsonContentType(String contentType) {
        if (contentType == null) {
            throw invalidRequest();
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            if (!"application".equalsIgnoreCase(mediaType.getType())
                    || !"json".equalsIgnoreCase(mediaType.getSubtype())) {
                throw invalidRequest();
            }
        } catch (InvalidMediaTypeException exception) {
            throw invalidRequest();
        }
    }

    private static byte[] readBoundedBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_REQUEST_BODY_BYTES) {
            throw invalidRequest();
        }
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BODY_BYTES_WITH_SENTINEL);
            if (body.length > MAX_REQUEST_BODY_BYTES) {
                throw invalidRequest();
            }
            return body;
        } catch (java.io.IOException exception) {
            throw invalidRequest();
        }
    }

    private JsonNode parsePayload(byte[] body) {
        try {
            JsonNode payload = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body);
            if (payload == null || !payload.isObject()) {
                throw invalidRequest();
            }
            return payload;
        } catch (JacksonException exception) {
            throw invalidRequest();
        }
    }

    private static String normalizedEmail(JsonNode payload) {
        JsonNode email = payload.get("email");
        if (email == null || !email.isString()) {
            throw invalidRequest();
        }
        try {
            return UserAccount.normalizeEmail(email.stringValue());
        } catch (IllegalArgumentException exception) {
            throw invalidRequest();
        }
    }

    private static String rawPassword(JsonNode payload) {
        JsonNode password = payload.get("password");
        if (password == null || !password.isString()) {
            throw invalidRequest();
        }

        String rawPassword = password.stringValue();
        if (rawPassword.isEmpty()
                || rawPassword.codePointCount(0, rawPassword.length()) > MAX_PASSWORD_CODE_POINTS) {
            throw invalidRequest();
        }
        return rawPassword;
    }

    private static InvalidLoginRequestException invalidRequest() {
        return new InvalidLoginRequestException();
    }
}
