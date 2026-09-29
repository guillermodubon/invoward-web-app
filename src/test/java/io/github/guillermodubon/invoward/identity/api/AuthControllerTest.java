package io.github.guillermodubon.invoward.identity.api;

import io.github.guillermodubon.invoward.identity.application.model.RegistrationOutcome;
import io.github.guillermodubon.invoward.identity.application.service.RegisterUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;

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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new AuthController(registerUserService))
                .setControllerAdvice(new AuthExceptionHandler())
                .build();
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

    private static String requestJson(String displayName, String email, String password) {
        return "{\"displayName\":\"" + displayName + "\",\"email\":\"" + email
                + "\",\"password\":\"" + password + "\"}";
    }
}
