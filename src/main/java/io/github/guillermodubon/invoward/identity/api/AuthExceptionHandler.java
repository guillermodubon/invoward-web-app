package io.github.guillermodubon.invoward.identity.api;

import io.github.guillermodubon.invoward.identity.api.model.ApiErrorResponse;
import io.github.guillermodubon.invoward.identity.api.model.ValidationErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.Map;
import java.util.TreeMap;

@RestControllerAdvice(assignableTypes = AuthController.class)
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
    public ResponseEntity<ValidationErrorResponse> handleDomainValidation(IllegalArgumentException exception) {
        String message = exception.getMessage();
        Map<String, String> fields = new TreeMap<>();
        if (message != null && message.startsWith("Display name")) {
            fields.put("displayName", "Invalid display name");
        } else if (message != null && message.startsWith("Email")) {
            fields.put("email", "Invalid email address");
        } else if (message != null && message.startsWith("Password")) {
            fields.put("password", "Password does not meet the requirements");
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

    private static String safeFieldMessage(FieldError error) {
        return switch (error.getField()) {
            case "displayName" -> "NotBlank".equals(error.getCode())
                    ? "Display name is required" : "Invalid display name";
            case "email" -> switch (error.getCode()) {
                case "NotBlank" -> "Email is required";
                case "Email" -> "Invalid email format";
                default -> "Invalid email address";
            };
            case "password" -> "Password is required";
            default -> null;
        };
    }
}
