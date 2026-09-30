package io.github.guillermodubon.invoward.identity.api;

import io.github.guillermodubon.invoward.identity.api.model.ApiErrorResponse;
import io.github.guillermodubon.invoward.identity.api.model.ValidationErrorResponse;
import io.github.guillermodubon.invoward.identity.application.exception.InvalidPasswordResetTokenException;
import io.github.guillermodubon.invoward.identity.application.exception.InvalidVerificationTokenException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.Map;
import java.util.TreeMap;

@RestControllerAdvice(assignableTypes = {AuthController.class, AccountController.class})
public class AuthExceptionHandler {

    private static final String VALIDATION_MESSAGE = "The request contains invalid data.";
    private static final String INVALID_REQUEST_MESSAGE = "The request could not be processed.";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new TreeMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            String safeMessage = safeFieldMessage(error);
            if (safeMessage != null) {
                fields.putIfAbsent(error.getField(), safeMessage);
            }
        }
        return ResponseEntity.badRequest().body(
                new ValidationErrorResponse("VALIDATION_FAILED", VALIDATION_MESSAGE, fields));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ValidationErrorResponse> handleDomainValidation(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        String message = exception.getMessage();
        Map<String, String> fields = new TreeMap<>();
        if (message != null && message.startsWith("Display name")) {
            fields.put("displayName", "Invalid display name");
        } else if (message != null && message.startsWith("Email")) {
            fields.put(isChangeEmailRequest(request) ? "newEmail" : "email", "Invalid email address");
        } else if (message != null && message.startsWith("Password")) {
            String field = isPasswordChangeRequest(request) ? "newPassword" : "password";
            fields.put(field, "Password does not meet the requirements");
        } else {
            return ResponseEntity.badRequest().body(
                    new ValidationErrorResponse("INVALID_REQUEST", INVALID_REQUEST_MESSAGE, Map.of()));
        }
        return ResponseEntity.badRequest().body(
                new ValidationErrorResponse("VALIDATION_FAILED", VALIDATION_MESSAGE, fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("INVALID_REQUEST", INVALID_REQUEST_MESSAGE));
    }

    @ExceptionHandler(InvalidVerificationTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidVerificationToken(
            InvalidVerificationTokenException exception) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse(
                "VERIFICATION_TOKEN_INVALID", "The verification link is invalid or expired."));
    }

    @ExceptionHandler(InvalidPasswordResetTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidPasswordResetToken(
            InvalidPasswordResetTokenException exception) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse(
                "RESET_TOKEN_INVALID", "The password reset link is invalid or expired."));
    }

    private static String safeFieldMessage(FieldError error) {
        return switch (error.getField()) {
            case "displayName" -> "NotBlank".equals(error.getCode())
                    ? "Display name is required" : "Invalid display name";
            case "email" -> safeEmailMessage(error);
            case "password" -> "Password is required";
            case "currentPassword" -> "Current password is required";
            case "newPassword" -> "Password is required";
            case "token" -> "Token is required";
            case "newEmail" -> safeEmailMessage(error);
            default -> null;
        };
    }

    private static boolean isChangeEmailRequest(HttpServletRequest request) {
        return request.getRequestURI() != null
                && request.getRequestURI().endsWith("/api/account/change-email");
    }

    private static boolean isPasswordChangeRequest(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        return requestUri != null
                && (requestUri.endsWith("/api/auth/reset-password")
                        || requestUri.endsWith("/api/account/change-password"));
    }

    private static String safeEmailMessage(FieldError error) {
        Object rejectedValue = error.getRejectedValue();
        if (!(rejectedValue instanceof String value) || value.isBlank()) {
            return "Email is required";
        }
        return switch (error.getCode()) {
            case "NotBlank" -> "Email is required";
            case "Email" -> "Invalid email format";
            default -> "Invalid email address";
        };
    }
}
