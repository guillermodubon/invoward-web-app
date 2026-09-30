package io.github.guillermodubon.invoward.identity.api;

import io.github.guillermodubon.invoward.identity.api.model.RegisterRequest;
import io.github.guillermodubon.invoward.identity.api.model.RegistrationAcceptedResponse;
import io.github.guillermodubon.invoward.identity.api.model.ActionAcceptedResponse;
import io.github.guillermodubon.invoward.identity.api.model.CsrfTokenResponse;
import io.github.guillermodubon.invoward.identity.api.model.CurrentUserResponse;
import io.github.guillermodubon.invoward.identity.api.model.ForgotPasswordRequest;
import io.github.guillermodubon.invoward.identity.api.model.ResetPasswordRequest;
import io.github.guillermodubon.invoward.identity.api.model.ResendVerificationRequest;
import io.github.guillermodubon.invoward.identity.api.model.VerifyEmailRequest;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.service.CurrentAccountService;
import io.github.guillermodubon.invoward.identity.application.service.ForgotPasswordService;
import io.github.guillermodubon.invoward.identity.application.service.RegisterUserService;
import io.github.guillermodubon.invoward.identity.application.service.ResetPasswordService;
import io.github.guillermodubon.invoward.identity.application.service.ResendRegistrationVerificationService;
import io.github.guillermodubon.invoward.identity.application.service.VerifyRegistrationEmailService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegisterUserService registerUserService;
    private final CurrentAccountService currentAccountService;
    private final VerifyRegistrationEmailService verifyRegistrationEmailService;
    private final ResendRegistrationVerificationService resendRegistrationVerificationService;
    private final ForgotPasswordService forgotPasswordService;
    private final ResetPasswordService resetPasswordService;

    public AuthController(
            RegisterUserService registerUserService,
            CurrentAccountService currentAccountService,
            VerifyRegistrationEmailService verifyRegistrationEmailService,
            ResendRegistrationVerificationService resendRegistrationVerificationService,
            ForgotPasswordService forgotPasswordService,
            ResetPasswordService resetPasswordService) {
        this.registerUserService = Objects.requireNonNull(registerUserService);
        this.currentAccountService = Objects.requireNonNull(currentAccountService);
        this.verifyRegistrationEmailService = Objects.requireNonNull(verifyRegistrationEmailService);
        this.resendRegistrationVerificationService = Objects.requireNonNull(resendRegistrationVerificationService);
        this.forgotPasswordService = Objects.requireNonNull(forgotPasswordService);
        this.resetPasswordService = Objects.requireNonNull(resetPasswordService);
    }

    @GetMapping(path = "/csrf", produces = MediaType.APPLICATION_JSON_VALUE)
    public CsrfTokenResponse csrf(@RequestAttribute("_csrf") CsrfToken csrfToken) {
        return new CsrfTokenResponse(csrfToken.getToken(), csrfToken.getHeaderName());
    }

    @GetMapping(path = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public CurrentUserResponse currentUser(@AuthenticationPrincipal AuthenticatedIdentity identity) {
        AuthenticatedIdentity authenticatedIdentity = Objects.requireNonNull(identity);
        return CurrentUserResponse.from(currentAccountService.get(authenticatedIdentity.userId()));
    }

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RegistrationAcceptedResponse> register(
            @Valid @RequestBody RegisterRequest request) {
        registerUserService.register(request.displayName(), request.email(), request.password());
        return ResponseEntity.accepted().body(RegistrationAcceptedResponse.generic());
    }

    @PostMapping(path = "/verify-email", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        verifyRegistrationEmailService.verify(request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/resend-verification", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ActionAcceptedResponse> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request) {
        resendRegistrationVerificationService.resend(request.email());
        return ResponseEntity.accepted().body(ActionAcceptedResponse.verificationResend());
    }

    @PostMapping(path = "/forgot-password", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ActionAcceptedResponse> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        forgotPasswordService.request(request.email());
        return ResponseEntity.accepted().body(ActionAcceptedResponse.passwordReset());
    }

    @PostMapping(path = "/reset-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        resetPasswordService.reset(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
