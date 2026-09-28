package io.github.guillermodubon.invoward.notification.infrastructure.email;

import io.github.guillermodubon.invoward.notification.infrastructure.email.disabled.DisabledEmailSender;
import io.github.guillermodubon.invoward.notification.infrastructure.email.gmail.GmailApiProperties;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({EmailProperties.class, GmailApiProperties.class})
public class EmailConfiguration {

    private static final Duration MAX_PROVIDER_TIMEOUT = Duration.ofSeconds(60);

    public EmailConfiguration(EmailProperties emailProperties, GmailApiProperties gmailApiProperties) {
        if (emailProperties.provider() == EmailProvider.GMAIL) {
            validateGmailConfiguration(emailProperties, gmailApiProperties);
        }
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "invoward.email",
            name = "provider",
            havingValue = "disabled",
            matchIfMissing = true)
    public DisabledEmailSender disabledEmailSender() {
        return new DisabledEmailSender();
    }

    private static void validateGmailConfiguration(
            EmailProperties emailProperties,
            GmailApiProperties gmailApiProperties) {
        requireConfigured(gmailApiProperties.clientId(), "GMAIL_CLIENT_ID");
        requireConfigured(gmailApiProperties.clientSecret(), "GMAIL_CLIENT_SECRET");
        requireConfigured(gmailApiProperties.refreshToken(), "GMAIL_REFRESH_TOKEN");
        requireConfigured(gmailApiProperties.senderAddress(), "GMAIL_SENDER_ADDRESS");
        validateSenderAddress(gmailApiProperties.senderAddress());
        validateFromName(emailProperties.fromName());
        validateTimeout(gmailApiProperties.connectTimeout(), "GMAIL_CONNECT_TIMEOUT");
        validateTimeout(gmailApiProperties.readTimeout(), "GMAIL_READ_TIMEOUT");
    }

    private static void requireConfigured(String value, String environmentVariable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Gmail email provider requires " + environmentVariable);
        }
    }

    private static void validateSenderAddress(String senderAddress) {
        if (containsHeaderControlCharacter(senderAddress)) {
            throw new IllegalStateException("GMAIL_SENDER_ADDRESS must be a single valid email address");
        }

        try {
            String candidate = senderAddress.strip();
            InternetAddress[] addresses = InternetAddress.parse(candidate, true);
            if (addresses.length != 1 || addresses[0].isGroup() || addresses[0].getPersonal() != null) {
                throw new IllegalStateException("GMAIL_SENDER_ADDRESS must be a single valid email address");
            }
            addresses[0].validate();
            if (!candidate.equals(addresses[0].getAddress())) {
                throw new IllegalStateException("GMAIL_SENDER_ADDRESS must be a single valid email address");
            }
        } catch (AddressException exception) {
            throw new IllegalStateException("GMAIL_SENDER_ADDRESS must be a single valid email address");
        }
    }

    private static void validateFromName(String fromName) {
        if (fromName != null && containsHeaderControlCharacter(fromName)) {
            throw new IllegalStateException("EMAIL_FROM_NAME must not contain CR, LF, or NUL");
        }
    }

    private static boolean containsHeaderControlCharacter(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0;
    }

    private static void validateTimeout(Duration timeout, String environmentVariable) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(MAX_PROVIDER_TIMEOUT) > 0) {
            throw new IllegalStateException(environmentVariable + " must be greater than 0 and at most 60s");
        }
    }
}
