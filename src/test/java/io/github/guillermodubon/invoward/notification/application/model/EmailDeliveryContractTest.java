package io.github.guillermodubon.invoward.notification.application.model;

import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryException;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import io.github.guillermodubon.invoward.notification.application.port.EmailSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailDeliveryContractTest {

    @Test
    void receiptRequiresNonblankProviderMessageId() {
        assertEquals("provider-id", new EmailDeliveryReceipt("provider-id").providerMessageId());
        assertThrows(IllegalArgumentException.class, () -> new EmailDeliveryReceipt(null));
        assertThrows(IllegalArgumentException.class, () -> new EmailDeliveryReceipt(" \t"));
    }

    @Test
    void senderPortUsesSynchronousProviderNeutralContract() throws NoSuchMethodException {
        Method send = EmailSender.class.getMethod("send", TransactionalEmail.class);

        assertEquals(EmailDeliveryReceipt.class, send.getReturnType());
        assertEquals(0, send.getExceptionTypes().length);
    }

    @Test
    void failureRetryabilityMatchesApprovedClassification() {
        assertFalse(EmailDeliveryFailure.INVALID_MESSAGE.isRetryable());
        assertFalse(EmailDeliveryFailure.AUTHENTICATION.isRetryable());
        assertFalse(EmailDeliveryFailure.AUTHORIZATION.isRetryable());
        assertTrue(EmailDeliveryFailure.RATE_LIMITED.isRetryable());
        assertTrue(EmailDeliveryFailure.PROVIDER_UNAVAILABLE.isRetryable());
        assertTrue(EmailDeliveryFailure.TIMEOUT.isRetryable());
        assertFalse(EmailDeliveryFailure.CONFIGURATION.isRetryable());
        assertFalse(EmailDeliveryFailure.UNKNOWN.isRetryable());
    }

    @Test
    void exceptionExposesSafeFailureDetailsAndPreservesCauseWithoutLeakingItInToString() {
        RuntimeException cause = new RuntimeException("refresh_token=private-token");
        EmailDeliveryException exception = new EmailDeliveryException(
                EmailDeliveryFailure.PROVIDER_UNAVAILABLE, "Email provider is temporarily unavailable", cause);

        assertEquals(EmailDeliveryFailure.PROVIDER_UNAVAILABLE, exception.failure());
        assertEquals("Email provider is temporarily unavailable", exception.safeMessage());
        assertTrue(exception.retryable());
        assertEquals(cause, exception.getCause());
        assertFalse(exception.toString().contains("private-token"));
        assertFalse(exception.toString().contains("temporarily unavailable"));
    }

    @Test
    void exceptionWithoutCauseIsSupported() {
        EmailDeliveryException exception = new EmailDeliveryException(
                EmailDeliveryFailure.CONFIGURATION, "Email delivery is not configured");

        assertFalse(exception.retryable());
        assertNull(exception.getCause());
        assertNotNull(exception.toString());
    }

    @Test
    void exceptionRequiresFailureAndNonblankSafeMessage() {
        assertThrows(NullPointerException.class,
                () -> new EmailDeliveryException(null, "Delivery failed"));
        assertThrows(IllegalArgumentException.class,
                () -> new EmailDeliveryException(EmailDeliveryFailure.UNKNOWN, " "));
    }
}
