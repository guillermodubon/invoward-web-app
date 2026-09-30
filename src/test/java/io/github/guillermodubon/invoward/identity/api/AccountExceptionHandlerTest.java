package io.github.guillermodubon.invoward.identity.api;

import io.github.guillermodubon.invoward.identity.application.exception.InvalidEmailChangeTokenException;
import io.github.guillermodubon.invoward.identity.application.exception.ReauthenticationFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AccountExceptionHandlerTest {

    private final MockMvc mockMvc = standaloneSetup(new FailureController())
            .setControllerAdvice(new AccountExceptionHandler())
            .build();

    @Test
    void mapsReauthenticationFailureToTheStableForbiddenContract() throws Exception {
        mockMvc.perform(get("/test/reauthentication-failure"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().json("""
                        {"code":"REAUTHENTICATION_FAILED","message":"The current password could not be verified."}
                        """));
    }

    @Test
    void mapsAllEmailChangeTokenFailuresToTheSameSafeBadRequest() throws Exception {
        mockMvc.perform(get("/test/invalid-email-change-token"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().json("""
                        {"code":"EMAIL_CHANGE_TOKEN_INVALID","message":"The email change could not be completed."}
                        """));
    }

    @RestController
    static class FailureController {

        @GetMapping("/test/reauthentication-failure")
        void reauthenticationFailure() {
            throw new ReauthenticationFailedException();
        }

        @GetMapping("/test/invalid-email-change-token")
        void invalidEmailChangeToken() {
            throw new InvalidEmailChangeTokenException();
        }
    }
}
