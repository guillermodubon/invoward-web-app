package io.github.guillermodubon.invoward.identity.api;

import io.github.guillermodubon.invoward.identity.api.model.ApiErrorResponse;
import io.github.guillermodubon.invoward.identity.application.exception.AccountConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps optimistic account update conflicts to the stable Identity API error contract. */
@RestControllerAdvice(basePackageClasses = AuthController.class)
public class AccountConflictExceptionHandler {

    private static final String CONFLICT_MESSAGE =
            "The account changed while the request was being processed. Please try again.";

    @ExceptionHandler(AccountConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleAccountConflict(AccountConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("ACCOUNT_CONFLICT", CONFLICT_MESSAGE));
    }
}
