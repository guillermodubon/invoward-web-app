package io.github.guillermodubon.invoward.notification.infrastructure.email.gmail;

import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.http.GenericUrl;
import com.google.api.services.gmail.Gmail;
import io.github.guillermodubon.invoward.notification.application.port.EmailSender;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailConfiguration;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailProperties;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GmailApiClientFactoryTest {

    private static final String CLIENT_ID = "fake-client-id";
    private static final String CLIENT_SECRET = "fake-client-secret";
    private static final String REFRESH_TOKEN = "fake-refresh-token";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    EmailConfiguration.class,
                    GmailApiConfiguration.class,
                    GmailMessageDispatcher.class);

    @Test
    void buildsSingletonGmailClientOnlyWhenGmailProviderIsSelected() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(EmailProvider.DISABLED, context.getBean(EmailProperties.class).provider());
            assertEquals(0, context.getBeansOfType(Gmail.class).size());
        });

        contextRunner.withPropertyValues(gmailProperties()).run(context -> {
            assertNull(context.getStartupFailure());
            Gmail client = context.getBean(Gmail.class);

            assertEquals("InvoWard", client.getApplicationName());
            assertEquals(1, context.getBeansOfType(Gmail.class).size());
            assertEquals(1, context.getBeansOfType(EmailSender.class).size());
            assertInstanceOf(GmailApiEmailAdapter.class, context.getBean(EmailSender.class));
        });
    }

    @Test
    void missingGmailConfigurationFailsBeforeClientIsAvailable() {
        contextRunner.withPropertyValues("invoward.email.provider=gmail").run(context -> {
            Throwable startupFailure = context.getStartupFailure();

            assertNotNull(startupFailure);
            assertTrue(exceptionMessages(startupFailure).contains("GMAIL_CLIENT_ID"));
        });
    }

    @Test
    void clientConstructionDoesNotCreateOrExecuteNetworkRequests() {
        CountingHttpTransport transport = new CountingHttpTransport();
        Gmail client = new GmailApiClientFactory(transport).createClient(properties());

        assertNotNull(client);
        assertEquals(0, transport.requestCount());
        assertEquals(0, transport.executionCount());
    }

    @Test
    void requestInitializerAppliesConfiguredTimeoutsWithoutRefreshingCredentials() throws Exception {
        CountingHttpTransport transport = new CountingHttpTransport();
        GmailApiProperties properties = properties();
        HttpRequestInitializer requestInitializer = GmailApiClientFactory.createRequestInitializer(
                request -> { }, properties);
        HttpRequest request = transport.createRequestFactory()
                .buildRequest("GET", new GenericUrl("https://example.test/resource"), null);

        requestInitializer.initialize(request);

        assertEquals(7_000, request.getConnectTimeout());
        assertEquals(20_000, request.getReadTimeout());
        assertEquals(0, transport.executionCount());
    }

    @Test
    void requestInitializerAppliesApprovedDefaultTimeouts() throws Exception {
        CountingHttpTransport transport = new CountingHttpTransport();
        GmailApiProperties properties = new GmailApiProperties(
                CLIENT_ID, CLIENT_SECRET, REFRESH_TOKEN, "sender@example.com",
                Duration.ofSeconds(5), Duration.ofSeconds(20));
        HttpRequestInitializer requestInitializer = GmailApiClientFactory.createRequestInitializer(
                request -> { }, properties);
        HttpRequest request = transport.createRequestFactory()
                .buildRequest("GET", new GenericUrl("https://example.test/resource"), null);

        requestInitializer.initialize(request);

        assertEquals(5_000, request.getConnectTimeout());
        assertEquals(20_000, request.getReadTimeout());
        assertEquals(0, transport.executionCount());
    }

    @Test
    void doesNotExposeCredentialsAsSpringBeansOrInPropertyStringRepresentation() {
        contextRunner.withPropertyValues(gmailProperties()).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(0, context.getBeansOfType(com.google.auth.oauth2.UserCredentials.class).size());

            String propertiesString = context.getBean(GmailApiProperties.class).toString();
            assertFalse(propertiesString.contains(CLIENT_ID));
            assertFalse(propertiesString.contains(CLIENT_SECRET));
            assertFalse(propertiesString.contains(REFRESH_TOKEN));
        });
    }

    private static String[] gmailProperties() {
        return new String[]{
                "invoward.email.provider=gmail",
                "invoward.email.gmail.client-id=" + CLIENT_ID,
                "invoward.email.gmail.client-secret=" + CLIENT_SECRET,
                "invoward.email.gmail.refresh-token=" + REFRESH_TOKEN,
                "invoward.email.gmail.sender-address=sender@example.com",
                "invoward.email.gmail.connect-timeout=7s",
                "invoward.email.gmail.read-timeout=20s"
        };
    }

    private static GmailApiProperties properties() {
        return new GmailApiProperties(
                CLIENT_ID, CLIENT_SECRET, REFRESH_TOKEN, "sender@example.com",
                Duration.ofSeconds(7), Duration.ofSeconds(20));
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

    private static final class CountingHttpTransport extends HttpTransport {

        private final AtomicInteger requestCount = new AtomicInteger();
        private final AtomicInteger executionCount = new AtomicInteger();

        @Override
        protected LowLevelHttpRequest buildRequest(String method, String url) {
            requestCount.incrementAndGet();
            return new LowLevelHttpRequest() {
                @Override
                public void addHeader(String name, String value) {
                    // The test does not need to inspect transport headers.
                }

                @Override
                public LowLevelHttpResponse execute() {
                    executionCount.incrementAndGet();
                    throw new AssertionError("Network execution is forbidden in this test");
                }
            };
        }

        private int requestCount() {
            return requestCount.get();
        }

        private int executionCount() {
            return executionCount.get();
        }
    }
}
