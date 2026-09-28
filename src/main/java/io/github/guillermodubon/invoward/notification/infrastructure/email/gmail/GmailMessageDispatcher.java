package io.github.guillermodubon.invoward.notification.infrastructure.email.gmail;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.Objects;

/** Sends an already encoded Gmail message without exposing provider responses. */
@Component
@ConditionalOnProperty(prefix = "invoward.email", name = "provider", havingValue = "gmail")
public final class GmailMessageDispatcher {

    private static final String USER_ID = "me";

    private final Gmail gmail;

    public GmailMessageDispatcher(Gmail gmail) {
        this.gmail = Objects.requireNonNull(gmail, "gmail must not be null");
    }

    public String dispatch(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new DispatchException(EmailDeliveryFailure.INVALID_MESSAGE);
        }

        try {
            HttpRequest request = gmail.users().messages()
                    .send(USER_ID, new Message().setRaw(rawMessage))
                    .buildHttpRequest();
            request.setNumberOfRetries(0);
            request.setLoggingEnabled(false);
            request.setCurlLoggingEnabled(false);

            HttpResponse response = request.execute();
            try {
                String messageId = response.parseAs(Message.class).getId();
                if (messageId == null || messageId.isBlank()) {
                    throw new DispatchException(EmailDeliveryFailure.UNKNOWN);
                }
                return messageId;
            } finally {
                disconnectQuietly(response);
            }
        } catch (DispatchException exception) {
            throw exception;
        } catch (GoogleJsonResponseException exception) {
            throw new DispatchException(classify(exception.getStatusCode(), exception.getDetails()));
        } catch (HttpResponseException exception) {
            throw new DispatchException(classify(exception.getStatusCode(), null));
        } catch (IOException exception) {
            throw new DispatchException(classify(exception));
        }
    }

    private static EmailDeliveryFailure classify(int statusCode, GoogleJsonError details) {
        return switch (statusCode) {
            case 400 -> EmailDeliveryFailure.INVALID_MESSAGE;
            case 401 -> EmailDeliveryFailure.AUTHENTICATION;
            case 403 -> containsRateLimitReason(details)
                    ? EmailDeliveryFailure.RATE_LIMITED
                    : EmailDeliveryFailure.AUTHORIZATION;
            case 429 -> EmailDeliveryFailure.RATE_LIMITED;
            default -> statusCode >= 500 && statusCode < 600
                    ? EmailDeliveryFailure.PROVIDER_UNAVAILABLE
                    : EmailDeliveryFailure.UNKNOWN;
        };
    }

    private static boolean containsRateLimitReason(GoogleJsonError details) {
        if (details == null || details.getErrors() == null) {
            return false;
        }

        return details.getErrors().stream()
                .map(GoogleJsonError.ErrorInfo::getReason)
                .filter(Objects::nonNull)
                .map(reason -> reason.toLowerCase(Locale.ROOT))
                .anyMatch(reason -> reason.equals("ratelimitexceeded")
                        || reason.equals("userratelimitexceeded")
                        || reason.equals("dailylimitexceeded")
                        || reason.equals("quotaexceeded"));
    }

    private static EmailDeliveryFailure classify(IOException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return EmailDeliveryFailure.TIMEOUT;
            }
            if (cause instanceof HttpResponseException responseException) {
                return classify(responseException.getStatusCode(), null);
            }
        }
        return EmailDeliveryFailure.UNKNOWN;
    }

    private static void disconnectQuietly(HttpResponse response) {
        try {
            response.disconnect();
        } catch (IOException ignored) {
            // The provider response has already been consumed; cleanup failure cannot change its outcome.
        }
    }

    /** Package-local, sanitized failure for the Gmail adapter to translate. */
    static final class DispatchException extends RuntimeException {

        private final EmailDeliveryFailure failure;

        private DispatchException(EmailDeliveryFailure failure) {
            super("Gmail message dispatch failed: " + failure.name());
            this.failure = Objects.requireNonNull(failure, "failure must not be null");
        }

        EmailDeliveryFailure failure() {
            return failure;
        }
    }
}
