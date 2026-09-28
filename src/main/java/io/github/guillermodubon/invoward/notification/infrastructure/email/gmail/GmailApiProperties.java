package io.github.guillermodubon.invoward.notification.infrastructure.email.gmail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "invoward.email.gmail")
public record GmailApiProperties(
        String clientId,
        String clientSecret,
        String refreshToken,
        String senderAddress,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("20s") Duration readTimeout) {

    @Override
    public String toString() {
        return "GmailApiProperties[credentials=<redacted>, senderAddress=<redacted>, timeouts=<redacted>]";
    }
}
