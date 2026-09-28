package io.github.guillermodubon.invoward.notification.infrastructure.email;

import com.google.api.services.gmail.Gmail;
import com.google.auth.oauth2.UserCredentials;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryException;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import io.github.guillermodubon.invoward.notification.application.port.EmailSender;
import io.github.guillermodubon.invoward.notification.infrastructure.email.disabled.DisabledEmailSender;
import io.github.guillermodubon.invoward.notification.infrastructure.email.gmail.GmailApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailConfigurationTest {

    private static final String CLIENT_ID = "fake-client-id";
    private static final String CLIENT_SECRET = "fake-client-secret";
    private static final String REFRESH_TOKEN = "fake-refresh-token";
    private static final String SENDER_ADDRESS = "test@example.com";
    private static final String[] COMPLETE_GMAIL_PROPERTIES = {
            "invoward.email.provider=gmail",
            "invoward.email.from-name=InvoWard Test",
            "invoward.email.gmail.client-id=" + CLIENT_ID,
            "invoward.email.gmail.client-secret=" + CLIENT_SECRET,
            "invoward.email.gmail.refresh-token=" + REFRESH_TOKEN,
            "invoward.email.gmail.sender-address=" + SENDER_ADDRESS,
            "invoward.email.gmail.connect-timeout=7s",
            "invoward.email.gmail.read-timeout=20s"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EmailConfiguration.class);

    @Test
    void defaultsToDisabledAndRegistersDisabledSender() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(EmailProvider.DISABLED, context.getBean(EmailProperties.class).provider());
            assertEquals("InvoWard", context.getBean(EmailProperties.class).fromName());
            assertEquals(Duration.ofSeconds(5), context.getBean(GmailApiProperties.class).connectTimeout());
            assertEquals(Duration.ofSeconds(20), context.getBean(GmailApiProperties.class).readTimeout());
            assertInstanceOf(DisabledEmailSender.class, context.getBean(EmailSender.class));
            assertEquals(0, context.getBeansOfType(UserCredentials.class).size());
            assertEquals(0, context.getBeansOfType(Gmail.class).size());
        });
    }

    @Test
    void explicitDisabledProviderAllowsBlankGmailPropertiesAndFailsSendingExplicitly() {
        contextRunner.withPropertyValues(
                "invoward.email.provider=disabled",
                "invoward.email.gmail.client-id=",
                "invoward.email.gmail.client-secret=",
                "invoward.email.gmail.refresh-token=",
                "invoward.email.gmail.sender-address=").run(context -> {
            assertNull(context.getStartupFailure());
            assertInstanceOf(DisabledEmailSender.class, context.getBean(EmailSender.class));

            EmailDeliveryException exception = assertThrows(EmailDeliveryException.class,
                    () -> context.getBean(EmailSender.class).send(
                            new TransactionalEmail(SENDER_ADDRESS, "Test", "Body", null)));
            assertEquals(EmailDeliveryFailure.CONFIGURATION, exception.failure());
            assertFalse(exception.retryable());
        });
    }

    @Test
    void bindsAndValidatesCompleteGmailConfigurationWithoutCreatingSenderInfrastructure() {
        contextRunner.withPropertyValues(COMPLETE_GMAIL_PROPERTIES).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(EmailProvider.GMAIL, context.getBean(EmailProperties.class).provider());
            assertEquals("InvoWard Test", context.getBean(EmailProperties.class).fromName());

            GmailApiProperties properties = context.getBean(GmailApiProperties.class);
            assertEquals(CLIENT_ID, properties.clientId());
            assertEquals(CLIENT_SECRET, properties.clientSecret());
            assertEquals(REFRESH_TOKEN, properties.refreshToken());
            assertEquals(SENDER_ADDRESS, properties.senderAddress());
            assertEquals(Duration.ofSeconds(7), properties.connectTimeout());
            assertEquals(Duration.ofSeconds(20), properties.readTimeout());
            assertFalse(properties.toString().contains(CLIENT_ID));
            assertFalse(properties.toString().contains(CLIENT_SECRET));
            assertFalse(properties.toString().contains(REFRESH_TOKEN));
            assertEquals(0, context.getBeansOfType(EmailSender.class).size());
            assertEquals(0, context.getBeansOfType(UserCredentials.class).size());
            assertEquals(0, context.getBeansOfType(Gmail.class).size());
        });
    }

    @Test
    void eachMissingRequiredGmailPropertyFailsWithSafeVariableName() {
        String[][] requiredProperties = {
                {"invoward.email.gmail.client-id", "GMAIL_CLIENT_ID"},
                {"invoward.email.gmail.client-secret", "GMAIL_CLIENT_SECRET"},
                {"invoward.email.gmail.refresh-token", "GMAIL_REFRESH_TOKEN"},
                {"invoward.email.gmail.sender-address", "GMAIL_SENDER_ADDRESS"}
        };

        for (String[] requiredProperty : requiredProperties) {
            String[] values = withoutProperty(COMPLETE_GMAIL_PROPERTIES, requiredProperty[0]);
            assertConfigurationFails(values, requiredProperty[1]);
        }
    }

    @Test
    void invalidSenderAddressFailsWithoutEchoingConfiguredValues() {
        assertConfigurationFails(
                replacingProperty(COMPLETE_GMAIL_PROPERTIES,
                        "invoward.email.gmail.sender-address", "not-an-address"),
                "GMAIL_SENDER_ADDRESS");
    }

    @Test
    void senderAddressRejectsCrLfAndNul() {
        for (String control : List.of("\r", "\n", "\0")) {
            assertConfigurationFails(
                    replacingProperty(COMPLETE_GMAIL_PROPERTIES,
                            "invoward.email.gmail.sender-address", "test" + control + "@example.com"),
                    "GMAIL_SENDER_ADDRESS");
        }
    }

    @Test
    void fromNameRejectsCrLfAndNul() {
        for (String control : List.of("\r", "\n", "\0")) {
            assertConfigurationFails(
                    replacingProperty(COMPLETE_GMAIL_PROPERTIES,
                            "invoward.email.from-name", "InvoWard" + control + "Test"),
                    "EMAIL_FROM_NAME");
        }
    }

    @Test
    void connectAndReadTimeoutsMustBePositive() {
        for (String property : List.of(
                "invoward.email.gmail.connect-timeout",
                "invoward.email.gmail.read-timeout")) {
            for (String timeout : List.of("0s", "-1s")) {
                String variable = property.endsWith("connect-timeout")
                        ? "GMAIL_CONNECT_TIMEOUT"
                        : "GMAIL_READ_TIMEOUT";
                assertConfigurationFails(
                        replacingProperty(COMPLETE_GMAIL_PROPERTIES, property, timeout), variable);
            }
        }
    }

    @Test
    void connectAndReadTimeoutsCannotExceedSixtySeconds() {
        assertConfigurationFails(
                replacingProperty(COMPLETE_GMAIL_PROPERTIES,
                        "invoward.email.gmail.connect-timeout", "61s"),
                "GMAIL_CONNECT_TIMEOUT");
        assertConfigurationFails(
                replacingProperty(COMPLETE_GMAIL_PROPERTIES,
                        "invoward.email.gmail.read-timeout", "61s"),
                "GMAIL_READ_TIMEOUT");
    }

    @Test
    void sixtySecondTimeoutsAreAccepted() {
        contextRunner.withPropertyValues(
                replacingProperty(COMPLETE_GMAIL_PROPERTIES,
                        "invoward.email.gmail.connect-timeout", "60s"))
                .withPropertyValues("invoward.email.gmail.read-timeout=60s")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    GmailApiProperties properties = context.getBean(GmailApiProperties.class);
                    assertEquals(Duration.ofSeconds(60), properties.connectTimeout());
                    assertEquals(Duration.ofSeconds(60), properties.readTimeout());
                });
    }

    @Test
    void invalidProviderValueFailsConfigurationBinding() {
        contextRunner.withPropertyValues("invoward.email.provider=smtp").run(context -> {
            assertNotNull(context.getStartupFailure());
            assertNoCredentialValues(exceptionMessages(context.getStartupFailure()));
        });
    }

    private void assertConfigurationFails(String[] properties, String safeMessagePart) {
        contextRunner.withPropertyValues(properties).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure, "Expected configuration failure for " + safeMessagePart);

            String messages = exceptionMessages(failure);
            assertTrue(messages.contains(safeMessagePart), "Expected a safe configuration message");
            assertNoCredentialValues(messages);
        });
    }

    private static void assertNoCredentialValues(String messages) {
        assertFalse(messages.contains(CLIENT_ID));
        assertFalse(messages.contains(CLIENT_SECRET));
        assertFalse(messages.contains(REFRESH_TOKEN));
    }

    private static String exceptionMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
        }
        return messages.toString();
    }

    private static String[] withoutProperty(String[] properties, String propertyName) {
        return List.of(properties).stream()
                .filter(property -> !property.startsWith(propertyName + "="))
                .toArray(String[]::new);
    }

    private static String[] replacingProperty(String[] properties, String propertyName, String value) {
        List<String> replaced = new ArrayList<>(List.of(withoutProperty(properties, propertyName)));
        replaced.add(propertyName + "=" + value);
        return replaced.toArray(String[]::new);
    }
}
