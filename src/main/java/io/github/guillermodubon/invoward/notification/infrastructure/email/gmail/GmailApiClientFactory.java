package io.github.guillermodubon.invoward.notification.infrastructure.email.gmail;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;

import java.util.Objects;

/** Creates the authenticated Gmail API client without performing a request. */
public final class GmailApiClientFactory {

    private static final String APPLICATION_NAME = "InvoWard";

    private final HttpTransport httpTransport;

    public GmailApiClientFactory(HttpTransport httpTransport) {
        this.httpTransport = Objects.requireNonNull(httpTransport, "httpTransport must not be null");
    }

    public Gmail createClient(GmailApiProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");

        UserCredentials credentials = UserCredentials.newBuilder()
                .setClientId(properties.clientId())
                .setClientSecret(properties.clientSecret())
                .setRefreshToken(properties.refreshToken())
                .build();
        HttpCredentialsAdapter credentialsAdapter = new HttpCredentialsAdapter(credentials);

        return new Gmail.Builder(
                        httpTransport,
                        GsonFactory.getDefaultInstance(),
                        createRequestInitializer(credentialsAdapter, properties))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    static HttpRequestInitializer createRequestInitializer(
            HttpRequestInitializer credentialsInitializer,
            GmailApiProperties properties) {
        Objects.requireNonNull(credentialsInitializer, "credentialsInitializer must not be null");
        Objects.requireNonNull(properties, "properties must not be null");

        return request -> {
            credentialsInitializer.initialize(request);
            request.setConnectTimeout(Math.toIntExact(properties.connectTimeout().toMillis()));
            request.setReadTimeout(Math.toIntExact(properties.readTimeout().toMillis()));
        };
    }
}
