package io.github.guillermodubon.invoward.identity.api;

import io.github.guillermodubon.invoward.identity.api.model.RegisterRequest;
import io.github.guillermodubon.invoward.identity.api.model.RegistrationAcceptedResponse;
import io.github.guillermodubon.invoward.identity.api.model.CsrfTokenResponse;
import io.github.guillermodubon.invoward.identity.api.model.CurrentUserResponse;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.service.RegisterUserService;
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

    public AuthController(RegisterUserService registerUserService) {
        this.registerUserService = Objects.requireNonNull(registerUserService);
    }

    @GetMapping(path = "/csrf", produces = MediaType.APPLICATION_JSON_VALUE)
    public CsrfTokenResponse csrf(@RequestAttribute("_csrf") CsrfToken csrfToken) {
        return new CsrfTokenResponse(csrfToken.getToken(), csrfToken.getHeaderName());
    }

    @GetMapping(path = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public CurrentUserResponse currentUser(@AuthenticationPrincipal AuthenticatedIdentity identity) {
        return CurrentUserResponse.from(Objects.requireNonNull(identity));
    }

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RegistrationAcceptedResponse> register(
            @Valid @RequestBody RegisterRequest request) {
        registerUserService.register(request.displayName(), request.email(), request.password());
        return ResponseEntity.accepted().body(RegistrationAcceptedResponse.generic());
    }
}
