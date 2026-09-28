package io.github.guillermodubon.invoward.notification.infrastructure.email.gmail;

import com.google.api.services.gmail.Gmail;
import io.github.guillermodubon.invoward.notification.application.model.EmailDeliveryReceipt;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import io.github.guillermodubon.invoward.notification.application.port.EmailSender;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailConfiguration;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailProvider;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailProperties;
import io.github.guillermodubon.invoward.notification.infrastructure.email.disabled.DisabledEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmailProviderWiringTest {

    private static final String CLIENT_ID = "fake-client-id";
    private static final String CLIENT_SECRET = "fake-client-secret";
    private static final String REFRESH_TOKEN = "fake-refresh-token";
    private static final String SENDER_ADDRESS = "sender@example.com";
    private static final String PROVIDER_MESSAGE_ID = "fake-provider-message-id";
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
            .withUserConfiguration(EmailConfiguration.class, GmailApiConfiguration.class);

    @Test
    void defaultConfigurationStartsWithOnlyTheDisabledEmailSender() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(EmailProvider.DISABLED, context.getBean(EmailProperties.class).provider());
            assertInstanceOf(DisabledEmailSender.class, context.getBean(EmailSender.class));
            assertEquals(1, context.getBeansOfType(EmailSender.class).size());
            assertTrue(context.getBeansOfType(GmailApiEmailAdapter.class).isEmpty());
            assertTrue(context.getBeansOfType(Gmail.class).isEmpty());
        });
    }

    @Test
    void explicitDisabledProviderWithBlankGmailCredentialsKeepsGmailInfrastructureAbsent() {
        contextRunner.withPropertyValues(
                "invoward.email.provider=disabled",
                "invoward.email.gmail.client-id=",
                "invoward.email.gmail.client-secret=",
                "invoward.email.gmail.refresh-token=",
                "invoward.email.gmail.sender-address=").run(context -> {
            assertNull(context.getStartupFailure());
            assertInstanceOf(DisabledEmailSender.class, context.getBean(EmailSender.class));
            assertEquals(1, context.getBeansOfType(EmailSender.class).size());
            assertTrue(context.getBeansOfType(GmailApiEmailAdapter.class).isEmpty());
            assertTrue(context.getBeansOfType(Gmail.class).isEmpty());
            assertTrue(context.getBeansOfType(GmailMessageDispatcher.class).isEmpty());
        });
    }

    @Test
    void gmailProviderWiresOneAdapterAndUsesTheDispatcherWithoutNetwork() {
        contextRunner.withUserConfiguration(MockDispatcherConfiguration.class)
                .withPropertyValues(COMPLETE_GMAIL_PROPERTIES)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(EmailProvider.GMAIL, context.getBean(EmailProperties.class).provider());
                    assertEquals(1, context.getBeansOfType(EmailSender.class).size());
                    assertInstanceOf(GmailApiEmailAdapter.class, context.getBean(EmailSender.class));
                    assertEquals(1, context.getBeansOfType(GmailApiEmailAdapter.class).size());
                    assertNotNull(context.getBean(Gmail.class));

                    GmailMessageDispatcher dispatcher = context.getBean(GmailMessageDispatcher.class);
                    verifyNoInteractions(dispatcher);
                    when(dispatcher.dispatch(anyString())).thenReturn(PROVIDER_MESSAGE_ID);

                    EmailDeliveryReceipt receipt = context.getBean(EmailSender.class).send(
                            new TransactionalEmail("recipient@example.com", "Test", "Body", null));

                    assertEquals(PROVIDER_MESSAGE_ID, receipt.providerMessageId());
                    verify(dispatcher).dispatch(anyString());
                });
    }

    @Test
    void invalidGmailConfigurationFailsStartupWithoutExposingSuppliedCredentials() {
        contextRunner.withPropertyValues(
                "invoward.email.provider=gmail",
                "invoward.email.gmail.client-secret=" + CLIENT_SECRET,
                "invoward.email.gmail.refresh-token=" + REFRESH_TOKEN,
                "invoward.email.gmail.sender-address=" + SENDER_ADDRESS).run(context -> {
            Throwable failure = context.getStartupFailure();

            assertNotNull(failure);
            String messages = exceptionMessages(failure);
            assertTrue(messages.contains("GMAIL_CLIENT_ID"));
            assertFalse(messages.contains(CLIENT_SECRET));
            assertFalse(messages.contains(REFRESH_TOKEN));
        });
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

    @TestConfiguration(proxyBeanMethods = false)
    static class MockDispatcherConfiguration {

        @Bean
        GmailMessageDispatcher gmailMessageDispatcher() {
            return mock(GmailMessageDispatcher.class);
        }
    }
}
