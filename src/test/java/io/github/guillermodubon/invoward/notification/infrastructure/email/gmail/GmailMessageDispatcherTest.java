package io.github.guillermodubon.invoward.notification.infrastructure.email.gmail;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GmailMessageDispatcherTest {

    private static final String RAW_MESSAGE = "YWJjLWRlZi0xMjM";
    private static final String MESSAGE_ID = "gmail-message-id-123";
    private static final String SENSITIVE_RESPONSE_MARKER = "provider-private-response-marker";

    @Test
    void sendsRawMessageToGmailMessagesSendAsMeAndReturnsProviderId() {
        FakeHttpTransport transport = FakeHttpTransport.responding(200, "{\"id\":\"" + MESSAGE_ID + "\"}");
        GmailMessageDispatcher dispatcher = dispatcher(transport);

        String messageId = dispatcher.dispatch(RAW_MESSAGE);

        assertEquals(MESSAGE_ID, messageId);
        assertEquals(1, transport.executionCount());
        assertEquals("POST", transport.method());
        assertEquals("https://gmail.googleapis.com/gmail/v1/users/me/messages/send", transport.url());
        assertTrue(transport.requestBody().contains("\"raw\":\"" + RAW_MESSAGE + "\""), transport.requestBody());
    }

    @Test
    void classifiesGmailHttpResponseStatusesWithoutExposingProviderBodyOrRetrying() {
        Map<Integer, EmailDeliveryFailure> expectedFailures = Map.of(
                400, EmailDeliveryFailure.INVALID_MESSAGE,
                401, EmailDeliveryFailure.AUTHENTICATION,
                403, EmailDeliveryFailure.AUTHORIZATION,
                429, EmailDeliveryFailure.RATE_LIMITED,
                500, EmailDeliveryFailure.PROVIDER_UNAVAILABLE,
                503, EmailDeliveryFailure.PROVIDER_UNAVAILABLE);

        expectedFailures.forEach((statusCode, expectedFailure) -> {
            FakeHttpTransport transport = FakeHttpTransport.responding(
                    statusCode,
                    "{\"error\":{\"code\":" + statusCode + ",\"message\":\""
                            + SENSITIVE_RESPONSE_MARKER + "\"}}");
            GmailMessageDispatcher.DispatchException exception = assertThrows(
                    GmailMessageDispatcher.DispatchException.class,
                    () -> dispatcher(transport).dispatch(RAW_MESSAGE));

            assertEquals(expectedFailure, exception.failure());
            assertFalse(exception.getMessage().contains(SENSITIVE_RESPONSE_MARKER));
            assertNull(exception.getCause());
            assertEquals(1, transport.executionCount());
        });
    }

    @Test
    void classifiesExplicitQuotaReasonAsRateLimitedForForbiddenResponse() {
        FakeHttpTransport transport = FakeHttpTransport.responding(403,
                "{\"error\":{\"code\":403,\"message\":\""
                        + SENSITIVE_RESPONSE_MARKER
                        + "\",\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}");

        GmailMessageDispatcher.DispatchException exception = assertThrows(
                GmailMessageDispatcher.DispatchException.class,
                () -> dispatcher(transport).dispatch(RAW_MESSAGE));

        assertEquals(EmailDeliveryFailure.RATE_LIMITED, exception.failure());
        assertFalse(exception.getMessage().contains(SENSITIVE_RESPONSE_MARKER));
        assertEquals(1, transport.executionCount());
    }

    @Test
    void classifiesSocketTimeoutAsRetryableTimeoutWithoutRetryingRequest() {
        FakeHttpTransport transport = FakeHttpTransport.failing(new SocketTimeoutException(SENSITIVE_RESPONSE_MARKER));

        GmailMessageDispatcher.DispatchException exception = assertThrows(
                GmailMessageDispatcher.DispatchException.class,
                () -> dispatcher(transport).dispatch(RAW_MESSAGE));

        assertEquals(EmailDeliveryFailure.TIMEOUT, exception.failure());
        assertFalse(exception.getMessage().contains(SENSITIVE_RESPONSE_MARKER));
        assertNull(exception.getCause());
        assertEquals(1, transport.executionCount());
    }

    @Test
    void classifiesUnclassifiedIoFailureAsUnknownWithoutRetryingRequest() {
        FakeHttpTransport transport = FakeHttpTransport.failing(new IOException(SENSITIVE_RESPONSE_MARKER));

        GmailMessageDispatcher.DispatchException exception = assertThrows(
                GmailMessageDispatcher.DispatchException.class,
                () -> dispatcher(transport).dispatch(RAW_MESSAGE));

        assertEquals(EmailDeliveryFailure.UNKNOWN, exception.failure());
        assertFalse(exception.getMessage().contains(SENSITIVE_RESPONSE_MARKER));
        assertNull(exception.getCause());
        assertEquals(1, transport.executionCount());
    }

    @Test
    void rejectsMissingRawMessageBeforeMakingHttpRequest() {
        FakeHttpTransport transport = FakeHttpTransport.responding(200, "{\"id\":\"unused\"}");

        GmailMessageDispatcher.DispatchException exception = assertThrows(
                GmailMessageDispatcher.DispatchException.class,
                () -> dispatcher(transport).dispatch("  "));

        assertEquals(EmailDeliveryFailure.INVALID_MESSAGE, exception.failure());
        assertEquals(0, transport.executionCount());
        assertTrue(transport.url() == null);
    }

    private static GmailMessageDispatcher dispatcher(FakeHttpTransport transport) {
        Gmail gmail = new Gmail.Builder(transport, GsonFactory.getDefaultInstance(), null)
                .setApplicationName("InvoWard")
                .build();
        return new GmailMessageDispatcher(gmail);
    }

    private static final class FakeHttpTransport extends HttpTransport {

        private final int statusCode;
        private final String responseBody;
        private final IOException failure;
        private final AtomicInteger executionCount = new AtomicInteger();
        private String method;
        private String url;
        private String requestBody;

        private FakeHttpTransport(int statusCode, String responseBody, IOException failure) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
            this.failure = failure;
        }

        static FakeHttpTransport responding(int statusCode, String responseBody) {
            return new FakeHttpTransport(statusCode, responseBody, null);
        }

        static FakeHttpTransport failing(IOException failure) {
            return new FakeHttpTransport(0, null, failure);
        }

        @Override
        protected LowLevelHttpRequest buildRequest(String method, String url) {
            this.method = method;
            this.url = url;
            return new LowLevelHttpRequest() {
                @Override
                public void addHeader(String name, String value) {
                    // The dispatcher tests do not need to inspect request headers.
                }

                @Override
                public LowLevelHttpResponse execute() throws IOException {
                    executionCount.incrementAndGet();
                    captureRequestBody();
                    if (failure != null) {
                        throw failure;
                    }
                    return new FakeHttpResponse(statusCode, responseBody);
                }

                private void captureRequestBody() throws IOException {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    if (getStreamingContent() != null) {
                        getStreamingContent().writeTo(output);
                    }
                    byte[] bytes = output.toByteArray();
                    if ("gzip".equalsIgnoreCase(getContentEncoding())) {
                        try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                            bytes = gzip.readAllBytes();
                        }
                    }
                    requestBody = new String(bytes, StandardCharsets.UTF_8);
                }
            };
        }

        int executionCount() {
            return executionCount.get();
        }

        String method() {
            return method;
        }

        String url() {
            return url;
        }

        String requestBody() {
            return requestBody;
        }

    }

    private static final class FakeHttpResponse extends LowLevelHttpResponse {

        private final int statusCode;
        private final byte[] content;

        private FakeHttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.content = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public InputStream getContent() {
            return new java.io.ByteArrayInputStream(content);
        }

        @Override
        public String getContentEncoding() {
            return null;
        }

        @Override
        public long getContentLength() {
            return content.length;
        }

        @Override
        public String getContentType() {
            return "application/json; charset=UTF-8";
        }

        @Override
        public String getStatusLine() {
            return "HTTP/1.1 " + statusCode + " Fake";
        }

        @Override
        public int getStatusCode() {
            return statusCode;
        }

        @Override
        public String getReasonPhrase() {
            return "Fake";
        }

        @Override
        public int getHeaderCount() {
            return 0;
        }

        @Override
        public String getHeaderName(int index) {
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        public String getHeaderValue(int index) {
            throw new IndexOutOfBoundsException(index);
        }
    }
}
