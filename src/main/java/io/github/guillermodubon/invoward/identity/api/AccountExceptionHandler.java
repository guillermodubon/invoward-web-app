package io.github.guillermodubon.invoward.identity.api;

import io.github.guillermodubon.invoward.identity.api.model.ApiErrorResponse;
import io.github.guillermodubon.invoward.identity.application.exception.InvalidEmailChangeTokenException;
import io.github.guillermodubon.invoward.identity.application.exception.ReauthenticationFailedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps account-sensitive failures to stable responses without exposing credentials or target state. */
@RestControllerAdvice(basePackageClasses = AccountController.class)
public class AccountExceptionHandler {

    @ExceptionHandler(ReauthenticationFailedException.class)
    public ResponseEntity<ApiErrorResponse> handleReauthenticationFailure(
            ReauthenticationFailedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiErrorResponse(
                        "REAUTHENTICATION_FAILED", "The current password could not be verified."));
    }

    @ExceptionHandler(InvalidEmailChangeTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidEmailChangeToken(
            InvalidEmailChangeTokenException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse("EMAIL_CHANGE_TOKEN_INVALID", "The email change could not be completed."));
    }
}
