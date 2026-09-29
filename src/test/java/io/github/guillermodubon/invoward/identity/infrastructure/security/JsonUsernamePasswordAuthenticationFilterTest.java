package io.github.guillermodubon.invoward.identity.infrastructure.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JsonUsernamePasswordAuthenticationFilterTest {

    private static final String PASSWORD = "  correct horse 🐎 battery staple  ";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validJsonNormalizesEmailAndPreservesRawPasswordForAuthenticationManager() {
        AuthenticationManager manager = mock(AuthenticationManager.class);
        Authentication authenticated = UsernamePasswordAuthenticationToken.authenticated("principal", "", List.of());
        when(manager.authenticate(any(Authentication.class))).thenReturn(authenticated);
        JsonUsernamePasswordAuthenticationFilter filter = filter(manager, mock(AuthenticationSuccessHandler.class),
                new JsonAuthenticationFailureHandler(objectMapper));
        MockHttpServletRequest request = loginRequest("{\"email\":\"  Person@Example.com \",\"password\":\""
                + PASSWORD + "\"}", "application/json");

        Authentication result = filter.attemptAuthentication(request, new MockHttpServletResponse());

        assertEquals(authenticated, result);
        org.mockito.ArgumentCaptor<Authentication> captor = org.mockito.ArgumentCaptor.forClass(Authentication.class);
        verify(manager).authenticate(captor.capture());
        Authentication submitted = captor.getValue();
        assertInstanceOf(UsernamePasswordAuthenticationToken.class, submitted);
        assertFalse(submitted.isAuthenticated());
        assertEquals("person@example.com", submitted.getPrincipal());
        assertEquals(PASSWORD, submitted.getCredentials());
    }

    @Test
    void rejectsMalformedAndStructurallyInvalidRequestsWithoutCallingAuthenticationManager() throws Exception {
        List<String> invalidBodies = List.of(
                "",
                "{",
                "[]",
                "{\"password\":\"secret\"}",
                "{\"email\":\"person@example.com\"}",
                "{\"email\":\"person@example.com\",\"password\":\"\"}",
                "{\"email\":\"\" ,\"password\":\"secret\"}",
                "{\"email\":\"" + "a".repeat(UserAccount.MAX_EMAIL_CODE_POINTS + 1)
                        + "\",\"password\":\"secret\"}",
                "{\"email\":\"person@example.com\",\"password\":\"" + "😀".repeat(129) + "\"}",
                "{\"email\":\"person@example.com\",\"password\":\"secret\"} {}"
        );

        for (String body : invalidBodies) {
            assertInvalidRequest(body, "application/json", false);
        }
    }

    @Test
    void rejectsNonJsonAndMalformedContentTypes() throws Exception {
        assertInvalidRequest("{\"email\":\"person@example.com\",\"password\":\"secret\"}",
                "text/plain", false);
        assertInvalidRequest("{\"email\":\"person@example.com\",\"password\":\"secret\"}",
                "application/json; charset=broken; charset=duplicate", false);
    }

    @Test
    void rejectsBodiesOverEightKibEvenWhenContentLengthIsUnknown() throws Exception {
        String oversized = " ".repeat(JsonUsernamePasswordAuthenticationFilter.MAX_REQUEST_BODY_BYTES + 1);
        assertInvalidRequest(oversized, "application/json", false);
        assertInvalidRequest(oversized, "application/json", true);
    }

    @Test
    void wrongCredentialsReturnGenericUnauthorizedResponseAndSafeLog() throws Exception {
        AuthenticationManager manager = mock(AuthenticationManager.class);
        when(manager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("secret-provider-detail email@example.com"));
        JsonAuthenticationFailureHandler handler = new JsonAuthenticationFailureHandler(objectMapper);
        JsonUsernamePasswordAuthenticationFilter filter = filter(manager, mock(AuthenticationSuccessHandler.class), handler);
        MockHttpServletRequest request = loginRequest(
                "{\"email\":\"email@example.com\",\"password\":\"" + PASSWORD + "\"}", "application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Logger logger = (Logger) LoggerFactory.getLogger(JsonAuthenticationFailureHandler.class);
        Level oldLevel = logger.getLevel();
        boolean oldAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.setLevel(Level.WARN);
        logger.setAdditive(false);
        logger.addAppender(appender);
        try {
            filter.doFilter(request, response, new MockFilterChain());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(oldLevel);
            logger.setAdditive(oldAdditive);
        }

        assertEquals(401, response.getStatus());
        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals("AUTHENTICATION_FAILED", body.get("code").asString());
        assertEquals("Invalid email or password.", body.get("message").asString());
        String responseBody = response.getContentAsString(StandardCharsets.UTF_8);
        String capturedLogs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        for (String sensitiveValue : List.of(PASSWORD, "email@example.com", "secret-provider-detail")) {
            assertFalse(responseBody.contains(sensitiveValue));
            assertFalse(capturedLogs.contains(sensitiveValue));
        }
        assertEquals(List.of("operation=login result=failure"), appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    void successHandlerReturnsOnlySafeCurrentUserProjection() throws Exception {
        JsonAuthenticationSuccessHandler handler = new JsonAuthenticationSuccessHandler(objectMapper);
        AuthenticatedUserPrincipal principal = new AuthenticatedUserPrincipal(new UserAccount(
                UUID.fromString("a3d9151b-50d3-42c4-9c08-9637795af0c1"),
                "Person", "person@example.com", "$argon2id$private-hash", true, UserStatus.ACTIVE, 0,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")));
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

        assertEquals(200, response.getStatus());
        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(principal.userId().toString(), body.get("id").asString());
        assertEquals("Person", body.get("displayName").asString());
        assertEquals("person@example.com", body.get("email").asString());
        assertTrue(body.get("emailVerified").asBoolean());
        assertEquals("ACTIVE", body.get("status").asString());
        assertFalse(response.getContentAsString(StandardCharsets.UTF_8).contains("private-hash"));
        assertFalse(response.getContentAsString(StandardCharsets.UTF_8).toLowerCase().contains("session"));
        assertFalse(response.getContentAsString(StandardCharsets.UTF_8).toLowerCase().contains("csrf"));
    }

    private void assertInvalidRequest(String body, String contentType, boolean unknownLength) throws Exception {
        AuthenticationManager manager = mock(AuthenticationManager.class);
        JsonAuthenticationFailureHandler failureHandler = new JsonAuthenticationFailureHandler(objectMapper);
        JsonUsernamePasswordAuthenticationFilter filter = filter(manager, mock(AuthenticationSuccessHandler.class),
                failureHandler);
        MockHttpServletRequest request = unknownLength
                ? new UnknownLengthLoginRequest(body, contentType)
                : loginRequest(body, contentType);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(400, response.getStatus(), "body should be rejected: " + body.substring(0, Math.min(body.length(), 40)));
        JsonNode error = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals("INVALID_REQUEST", error.get("code").asString());
        assertEquals("The request could not be processed.", error.get("message").asString());
        verifyNoInteractions(manager);
    }

    private JsonUsernamePasswordAuthenticationFilter filter(
            AuthenticationManager manager,
            AuthenticationSuccessHandler successHandler,
            AuthenticationFailureHandler failureHandler) {
        return new JsonUsernamePasswordAuthenticationFilter(manager, objectMapper, successHandler, failureHandler);
    }

    private static MockHttpServletRequest loginRequest(String body, String contentType) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setServletPath("/api/auth/login");
        request.setContentType(contentType);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static final class UnknownLengthLoginRequest extends MockHttpServletRequest {

        private UnknownLengthLoginRequest(String body, String contentType) {
            super();
            setMethod("POST");
            setServletPath("/api/auth/login");
            setContentType(contentType);
            setContent(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }
    }
}
