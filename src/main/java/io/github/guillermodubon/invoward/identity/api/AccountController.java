package io.github.guillermodubon.invoward.identity.api;

import io.github.guillermodubon.invoward.identity.api.model.ActionAcceptedResponse;
import io.github.guillermodubon.invoward.identity.api.model.ChangeEmailRequest;
import io.github.guillermodubon.invoward.identity.api.model.ChangePasswordRequest;
import io.github.guillermodubon.invoward.identity.api.model.ConfirmEmailChangeRequest;
import io.github.guillermodubon.invoward.identity.api.model.CurrentUserResponse;
import io.github.guillermodubon.invoward.identity.api.model.UpdateProfileRequest;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.service.ChangePasswordService;
import io.github.guillermodubon.invoward.identity.application.service.ConfirmEmailChangeService;
import io.github.guillermodubon.invoward.identity.application.service.RequestEmailChangeService;
import io.github.guillermodubon.invoward.identity.application.service.UpdateProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Objects;

/** Authenticated account settings endpoints; mutations stay delegated to application services. */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private static final String SESSION_COOKIE_NAME = "INVOWARD_SESSION";
    private static final String SESSION_COOKIE_PATH = "/";

    private final UpdateProfileService updateProfileService;
    private final ChangePasswordService changePasswordService;
    private final RequestEmailChangeService requestEmailChangeService;
    private final ConfirmEmailChangeService confirmEmailChangeService;
    private final boolean secureSessionCookie;
    private final String sessionCookieSameSite;

    public AccountController(
            UpdateProfileService updateProfileService,
            ChangePasswordService changePasswordService,
            RequestEmailChangeService requestEmailChangeService,
            ConfirmEmailChangeService confirmEmailChangeService,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureSessionCookie,
            @Value("${server.servlet.session.cookie.same-site:lax}") String sessionCookieSameSite) {
        this.updateProfileService = Objects.requireNonNull(updateProfileService);
        this.changePasswordService = Objects.requireNonNull(changePasswordService);
        this.requestEmailChangeService = Objects.requireNonNull(requestEmailChangeService);
        this.confirmEmailChangeService = Objects.requireNonNull(confirmEmailChangeService);
        this.secureSessionCookie = secureSessionCookie;
        this.sessionCookieSameSite = Objects.requireNonNull(sessionCookieSameSite);
    }

    @PatchMapping(path = "/profile", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CurrentUserResponse> updateProfile(
            @AuthenticationPrincipal AuthenticatedIdentity identity,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(CurrentUserResponse.from(
                updateProfileService.updateDisplayName(identity.userId(), request.displayName())));
    }

    @PostMapping(path = "/change-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal AuthenticatedIdentity identity,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        changePasswordService.change(identity.userId(), request.currentPassword(), request.newPassword());
        terminateCurrentSession(servletRequest, servletResponse);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/change-email", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ActionAcceptedResponse> changeEmail(
            @AuthenticationPrincipal AuthenticatedIdentity identity,
            @Valid @RequestBody ChangeEmailRequest request) {
        requestEmailChangeService.request(identity.userId(), request.currentPassword(), request.newEmail());
        return ResponseEntity.accepted().body(ActionAcceptedResponse.emailChangeRequest());
    }

    @PostMapping(path = "/confirm-email-change", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> confirmEmailChange(
            @AuthenticationPrincipal AuthenticatedIdentity identity,
            @Valid @RequestBody ConfirmEmailChangeRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        confirmEmailChangeService.confirm(identity.userId(), request.token());
        terminateCurrentSession(servletRequest, servletResponse);
        return ResponseEntity.noContent().build();
    }

    private void terminateCurrentSession(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        ResponseCookie expiredSessionCookie = ResponseCookie.from(SESSION_COOKIE_NAME, "")
                .path(SESSION_COOKIE_PATH)
                .httpOnly(true)
                .secure(secureSessionCookie)
                .sameSite(sessionCookieSameSite)
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, expiredSessionCookie.toString());
    }
}
