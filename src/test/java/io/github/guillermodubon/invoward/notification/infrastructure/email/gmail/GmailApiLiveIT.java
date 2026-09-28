package io.github.guillermodubon.invoward.notification.infrastructure.email.gmail;

import com.google.api.services.gmail.Gmail;
import io.github.guillermodubon.invoward.notification.application.model.EmailDeliveryReceipt;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import io.github.guillermodubon.invoward.notification.application.port.EmailSender;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailConfiguration;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailProvider;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GmailApiLiveIT {

    private static final String LIVE_TEST_SUBJECT =
            "[InvoWard NON-PRODUCTION manual live test] Gmail API validation";
    private static final String LIVE_TEST_BODY =
            "This is a single non-production message for manually validating InvoWard Gmail API delivery.";

    @Test
    @EnabledIfEnvironmentVariable(named = "GMAIL_LIVE_TEST_ENABLED", matches = "true")
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
    void sendsOneNonProductionMessageThroughTheConfiguredGmailAdapter() {
        assertEquals("gmail", requiredEnvironmentVariable("EMAIL_PROVIDER"),
                "The live Gmail test requires EMAIL_PROVIDER=gmail");
        String recipient = requiredEnvironmentVariable("GMAIL_LIVE_TEST_RECIPIENT");
        String clientId = requiredEnvironmentVariable("GMAIL_CLIENT_ID");
        String clientSecret = requiredEnvironmentVariable("GMAIL_CLIENT_SECRET");
        String refreshToken = requiredEnvironmentVariable("GMAIL_REFRESH_TOKEN");
        String senderAddress = requiredEnvironmentVariable("GMAIL_SENDER_ADDRESS");

        new ApplicationContextRunner()
                .withUserConfiguration(
                        EmailConfiguration.class,
                        GmailApiConfiguration.class,
                        GmailMessageDispatcher.class)
                .withPropertyValues(
                        "invoward.email.provider=gmail",
                        "invoward.email.gmail.client-id=" + clientId,
                        "invoward.email.gmail.client-secret=" + clientSecret,
                        "invoward.email.gmail.refresh-token=" + refreshToken,
                        "invoward.email.gmail.sender-address=" + senderAddress)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(EmailProvider.GMAIL, context.getBean(EmailProperties.class).provider());
                    assertEquals(1, context.getBeansOfType(EmailSender.class).size());
                    assertInstanceOf(GmailApiEmailAdapter.class, context.getBean(EmailSender.class));
                    assertNotNull(context.getBean(Gmail.class));

                    EmailDeliveryReceipt receipt = context.getBean(EmailSender.class).send(
                            new TransactionalEmail(recipient, LIVE_TEST_SUBJECT, LIVE_TEST_BODY, null));

                    assertNotNull(receipt);
                    assertFalse(receipt.providerMessageId().isBlank());
                });
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set to run the opt-in Gmail live test");
        }
        return value;
    }
}
