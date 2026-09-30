package io.github.guillermodubon.invoward.identity.api;

import io.github.guillermodubon.invoward.identity.api.model.ActionAcceptedResponse;
import io.github.guillermodubon.invoward.identity.api.model.ChangeEmailRequest;
import io.github.guillermodubon.invoward.identity.api.model.ChangePasswordRequest;
import io.github.guillermodubon.invoward.identity.api.model.ConfirmEmailChangeRequest;
import io.github.guillermodubon.invoward.identity.api.model.CurrentUserResponse;
import io.github.guillermodubon.invoward.identity.api.model.UpdateProfileRequest;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.application.service.ChangePasswordService;
import io.github.guillermodubon.invoward.identity.application.service.ConfirmEmailChangeService;
import io.github.guillermodubon.invoward.identity.application.service.RequestEmailChangeService;
import io.github.guillermodubon.invoward.identity.application.service.UpdateProfileService;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    private static final UUID USER_ID = UUID.fromString("a3d9151b-50d3-42c4-9c08-9637795af0c1");
    private static final AuthenticatedIdentity IDENTITY = new CurrentAccount(
            USER_ID, "Login Snapshot", "login@example.com", true, UserStatus.ACTIVE);

    @Mock
    private UpdateProfileService updateProfileService;
    @Mock
    private ChangePasswordService changePasswordService;
    @Mock
    private RequestEmailChangeService requestEmailChangeService;
    @Mock
    private ConfirmEmailChangeService confirmEmailChangeService;

    private AccountController controller;

    @BeforeEach
    void setUp() {
        controller = new AccountController(
                updateProfileService,
                changePasswordService,
                requestEmailChangeService,
                confirmEmailChangeService,
                true,
                "lax");
    }

    @Test
    void profileReturnsOnlyUpdatedCurrentUserFieldsAndDoesNotInvalidateSession() {
        CurrentAccount updated = new CurrentAccount(
                USER_ID, "Updated Name", "login@example.com", true, UserStatus.ACTIVE);
        when(updateProfileService.updateDisplayName(USER_ID, "  Updated Name  ")).thenReturn(updated);

        ResponseEntity<CurrentUserResponse> response = controller.updateProfile(
                IDENTITY, new UpdateProfileRequest("  Updated Name  "));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(new CurrentUserResponse(USER_ID, "Updated Name", "login@example.com", true, "ACTIVE"),
                response.getBody());
        verify(updateProfileService).updateDisplayName(USER_ID, "  Updated Name  ");
    }

    @Test
    void passwordChangePreservesCredentialInputAndTerminatesCurrentSession() {
        String currentPassword = "  exact current passphrase  ";
        String newPassword = "  exact replacement passphrase  ";
        SessionRequest sessionRequest = sessionRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<Void> result = controller.changePassword(
                IDENTITY,
                new ChangePasswordRequest(currentPassword, newPassword),
                sessionRequest.request(),
                response);

        assertEquals(204, result.getStatusCode().value());
        verify(changePasswordService).change(USER_ID, currentPassword, newPassword);
        assertTrue(sessionRequest.session().isInvalid());
        assertSessionCookieExpired(response);
    }

    @Test
    void failedPasswordChangeDoesNotTerminateTheCurrentSession() {
        String currentPassword = "wrong current passphrase";
        SessionRequest sessionRequest = sessionRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        doThrow(new io.github.guillermodubon.invoward.identity.application.exception.ReauthenticationFailedException())
                .when(changePasswordService).change(USER_ID, currentPassword, "a new passphrase long enough");

        org.junit.jupiter.api.Assertions.assertThrows(
                io.github.guillermodubon.invoward.identity.application.exception.ReauthenticationFailedException.class,
                () -> controller.changePassword(
                        IDENTITY,
                        new ChangePasswordRequest(currentPassword, "a new passphrase long enough"),
                        sessionRequest.request(),
                        response));

        assertFalse(sessionRequest.session().isInvalid());
        assertNull(response.getHeader("Set-Cookie"));
    }

    @Test
    void changeEmailReturnsTheApprovedGenericAcceptedMessage() {
        ResponseEntity<ActionAcceptedResponse> response = controller.changeEmail(
                IDENTITY, new ChangeEmailRequest("current passphrase", "Target@Example.com"));

        assertEquals(202, response.getStatusCode().value());
        assertEquals("If the new address can be used, a confirmation email will be sent.",
                response.getBody().message());
        verify(requestEmailChangeService).request(USER_ID, "current passphrase", "Target@Example.com");
    }

    @Test
    void emailChangeConfirmationTerminatesCurrentSessionAfterSuccess() {
        String token = "A".repeat(43);
        SessionRequest sessionRequest = sessionRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<Void> result = controller.confirmEmailChange(
                IDENTITY, new ConfirmEmailChangeRequest(token), sessionRequest.request(), response);

        assertEquals(204, result.getStatusCode().value());
        verify(confirmEmailChangeService).confirm(USER_ID, token);
        assertTrue(sessionRequest.session().isInvalid());
        assertSessionCookieExpired(response);
    }

    private static SessionRequest sessionRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        return new SessionRequest(request, session);
    }

    private static void assertSessionCookieExpired(HttpServletResponse response) {
        String cookie = response.getHeader("Set-Cookie");
        assertNotNull(cookie);
        assertTrue(cookie.contains("INVOWARD_SESSION="));
        assertTrue(cookie.contains("Max-Age=0"));
        assertTrue(cookie.contains("Path=/"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=lax"));
    }

    private record SessionRequest(MockHttpServletRequest request, MockHttpSession session) {
    }
}
