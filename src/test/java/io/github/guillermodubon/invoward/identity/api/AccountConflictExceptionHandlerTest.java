package io.github.guillermodubon.invoward.identity.api;

import io.github.guillermodubon.invoward.identity.application.exception.AccountConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AccountConflictExceptionHandlerTest {

    private final MockMvc mockMvc = standaloneSetup(new ConflictController())
            .setControllerAdvice(new AccountConflictExceptionHandler())
            .build();

    @Test
    void mapsOptimisticAccountConflictToGenericHttp409Contract() throws Exception {
        mockMvc.perform(get("/test/account-conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().json("""
                        {"code":"ACCOUNT_CONFLICT","message":"The account changed while the request was being processed. Please try again."}
                        """));
    }

    @RestController
    static class ConflictController {

        @GetMapping("/test/account-conflict")
        void conflict() {
            throw new AccountConflictException();
        }
    }
}
