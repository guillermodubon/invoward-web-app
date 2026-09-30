package io.github.guillermodubon.invoward.identity.api;

import io.github.guillermodubon.invoward.identity.application.model.RegistrationOutcome;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.service.CurrentAccountService;
import io.github.guillermodubon.invoward.identity.api.model.CurrentUserResponse;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.identity.application.exception.InvalidPasswordResetTokenException;
import io.github.guillermodubon.invoward.identity.application.exception.InvalidVerificationTokenException;
import io.github.guillermodubon.invoward.identity.application.service.ForgotPasswordService;
import io.github.guillermodubon.invoward.identity.application.service.RegisterUserService;
import io.github.guillermodubon.invoward.identity.application.service.ResetPasswordService;
import io.github.guillermodubon.invoward.identity.application.service.ResendRegistrationVerificationService;
import io.github.guillermodubon.invoward.identity.application.service.VerifyRegistrationEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private static final String RAW_PASSWORD = "  a sufficiently long passphrase  ";

    @Mock
    private RegisterUserService registerUserService;

    @Mock
    private CurrentAccountService currentAccountService;

    @Mock
    private VerifyRegistrationEmailService verifyRegistrationEmailService;

    @Mock
    private ResendRegistrationVerificationService resendRegistrationVerificationService;

    @Mock
    private ForgotPasswordService forgotPasswordService;

    @Mock
    private ResetPasswordService resetPasswordService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new AuthController(
                registerUserService,
                currentAccountService,
                verifyRegistrationEmailService,
                resendRegistrationVerificationService,
                forgotPasswordService,
                resetPasswordService))
                .setControllerAdvice(new AuthExceptionHandler())
                .build();
    }

    @Test
    void currentUserLoadsFreshAccountStateUsingOnlyTheAuthenticatedUserId() {
        UUID userId = UUID.fromString("a3d9151b-50d3-42c4-9c08-9637795af0c1");
        AuthenticatedIdentity staleIdentity = new CurrentAccount(
                userId, "Old Display Name", "old@example.com", true, UserStatus.ACTIVE);
        CurrentAccount freshAccount = new CurrentAccount(
                userId, "Updated Display Name", "new@example.com", true, UserStatus.ACTIVE);
        when(currentAccountService.get(userId)).thenReturn(freshAccount);
        AuthController controller = new AuthController(
                registerUserService,
                currentAccountService,
                verifyRegistrationEmailService,
                resendRegistrationVerificationService,
                forgotPasswordService,
                resetPasswordService);

        assertEquals(new CurrentUserResponse(
                        userId, "Updated Display Name", "new@example.com", true, "ACTIVE"),
                controller.currentUser(staleIdentity));
        verify(currentAccountService).get(userId);
    }

    @Test
    void returnsSameGenericAcceptedResponseForNewAndExistingEmails() throws Exception {
        when(registerUserService.register("New User", "new@example.com", RAW_PASSWORD))
                .thenReturn(RegistrationOutcome.ACCEPTED);
        when(registerUserService.register("Existing User", "existing@example.com", RAW_PASSWORD))
                .thenReturn(RegistrationOutcome.ACCEPTED);

        String expected = "{\"message\":\"If the account can be created, check the email address for the next step.\"}";
        String newAccountResponse = mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(requestJson("New User", "new@example.com", RAW_PASSWORD)))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(not(containsString(RAW_PASSWORD))))
                .andReturn().getResponse().getContentAsString();
        String existingAccountResponse = mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(requestJson("Existing User", "existing@example.com", RAW_PASSWORD)))
                .andExpect(status().isAccepted())
                .andExpect(content().string(not(containsString(RAW_PASSWORD))))
                .andReturn().getResponse().getContentAsString();

        assertEquals(expected, newAccountResponse);
        assertEquals(newAccountResponse, existingAccountResponse);
        verify(registerUserService).register("New User", "new@example.com", RAW_PASSWORD);
        verify(registerUserService).register("Existing User", "existing@example.com", RAW_PASSWORD);
    }

    @Test
    void returnsSafeFieldErrorsForInvalidBasicRequestShape() throws Exception {
        String secret = "do not echo this password";

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(requestJson(" ", "not-an-email", secret)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("The request contains invalid data."))
                .andExpect(jsonPath("$.fields.displayName").value("Display name is required"))
                .andExpect(jsonPath("$.fields.email").value("Invalid email format"))
                .andExpect(content().string(not(containsString(secret))));
    }

    @Test
    void requiresPasswordWithoutEchoingSubmittedValue() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(requestJson("Valid User", "user@example.com", " ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.password").value("Password is required"));
    }

    @Test
    void mapsDomainPasswordPolicyFailureWithoutEchoingPasswordOrExceptionDetails() throws Exception {
        String invalidPassword = "short";
        doThrow(new IllegalArgumentException("Password must contain between 15 and 128 Unicode code points"))
                .when(registerUserService)
                .register("Valid User", "user@example.com", invalidPassword);

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(requestJson("Valid User", "user@example.com", invalidPassword)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.password").value("Password does not meet the requirements"))
                .andExpect(content().string(not(containsString(invalidPassword))))
                .andExpect(content().string(not(containsString("Unicode code points"))));
    }

    @Test
    void malformedJsonGetsSafeInvalidRequestResponse() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content("{\"password\":\"never echo\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("The request could not be processed."))
                .andExpect(content().string(not(containsString("never echo"))));
    }

    @Test
    void verificationEndpointReturnsNoContentAndMapsAllInvalidTokenStatesGenerically() throws Exception {
        String rawToken = "A".repeat(43);
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType("application/json")
                        .content("{\"token\":\"" + rawToken + "\"}"))
                .andExpect(status().isNoContent());
        verify(verifyRegistrationEmailService).verify(rawToken);

        doThrow(new InvalidVerificationTokenException())
                .when(verifyRegistrationEmailService).verify("B".repeat(43));
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType("application/json")
                        .content("{\"token\":\"" + "B".repeat(43) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"VERIFICATION_TOKEN_INVALID","message":"The verification link is invalid or expired."}
                        """))
                .andExpect(content().string(not(containsString("B".repeat(43)))));
    }

    @Test
    void resendVerificationReturnsSameAcceptedResponseForDifferentAccountEligibilityStates() throws Exception {
        String firstResponse = mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType("application/json")
                        .content("{\"email\":\"unknown@example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn().getResponse().getContentAsString();
        String secondResponse = mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType("application/json")
                        .content("{\"email\":\"Pending@Example.com\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertEquals("{\"message\":\"If the account is eligible, a verification email will be sent.\"}",
                firstResponse);
        assertEquals(firstResponse, secondResponse);
        verify(resendRegistrationVerificationService).resend("unknown@example.com");
        verify(resendRegistrationVerificationService).resend("Pending@Example.com");
    }

    @Test
    void forgotPasswordReturnsSameAcceptedResponseForDifferentAccountEligibilityStates() throws Exception {
        String firstResponse = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType("application/json")
                        .content("{\"email\":\"unknown@example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn().getResponse().getContentAsString();
        String secondResponse = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType("application/json")
                        .content("{\"email\":\"Active@Example.com\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertEquals(
                "{\"message\":\"If an eligible account exists, password reset instructions will be sent.\"}",
                firstResponse);
        assertEquals(firstResponse, secondResponse);
        verify(forgotPasswordService).request("unknown@example.com");
        verify(forgotPasswordService).request("Active@Example.com");
    }

    @Test
    void resetPasswordReturnsNoContentAndMapsInvalidTokensGenerically() throws Exception {
        String rawToken = "C".repeat(43);
        String rawPassword = "  an exact long passphrase  ";
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType("application/json")
                        .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\""
                                + rawPassword + "\"}"))
                .andExpect(status().isNoContent());
        verify(resetPasswordService).reset(rawToken, rawPassword);

        doThrow(new InvalidPasswordResetTokenException())
                .when(resetPasswordService).reset("D".repeat(43), rawPassword);
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType("application/json")
                        .content("{\"token\":\"" + "D".repeat(43) + "\",\"newPassword\":\""
                                + rawPassword + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"RESET_TOKEN_INVALID","message":"The password reset link is invalid or expired."}
                        """))
                .andExpect(content().string(not(containsString("D".repeat(43)))));
    }

    @Test
    void resetPasswordPolicyErrorUsesSafeNewPasswordFieldWithoutEchoingInput() throws Exception {
        String rawToken = "E".repeat(43);
        String invalidPassword = "short secret";
        doThrow(new IllegalArgumentException("Password must contain between 15 and 128 Unicode code points"))
                .when(resetPasswordService).reset(rawToken, invalidPassword);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType("application/json")
                        .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\""
                                + invalidPassword + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.newPassword").value("Password does not meet the requirements"))
                .andExpect(content().string(not(containsString(rawToken))))
                .andExpect(content().string(not(containsString(invalidPassword))))
                .andExpect(content().string(not(containsString("Unicode code points"))));
    }

    @Test
    void lifecycleDtosRejectInvalidShapeWithSafeFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.token").value("Token is required"));

        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType("application/json")
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").value("Invalid email format"));

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType("application/json")
                        .content("{\"email\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").value("Email is required"));

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType("application/json")
                        .content("{\"token\":\"" + "F".repeat(43) + "\",\"newPassword\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.newPassword").value("Password is required"));
    }

    @Test
    void requestDtosRedactTokensAndPasswordsFromStringRepresentation() {
        assertEquals("VerifyEmailRequest[token=[REDACTED]]",
                new io.github.guillermodubon.invoward.identity.api.model.VerifyEmailRequest("secret-token").toString());
        assertEquals("ResetPasswordRequest[token=[REDACTED], newPassword=[REDACTED]]",
                new io.github.guillermodubon.invoward.identity.api.model.ResetPasswordRequest(
                        "secret-token", "secret-password").toString());
    }

    private static String requestJson(String displayName, String email, String password) {
        return "{\"displayName\":\"" + displayName + "\",\"email\":\"" + email
                + "\",\"password\":\"" + password + "\"}";
    }
}
