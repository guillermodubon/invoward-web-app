package io.github.guillermodubon.invoward.notification.infrastructure.email.gmail;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.services.gmail.Gmail;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "invoward.email", name = "provider", havingValue = "gmail")
public class GmailApiConfiguration {

    @Bean
    @DependsOn("emailConfiguration")
    public Gmail gmailApiClient(GmailApiProperties properties) {
        HttpTransport transport = new NetHttpTransport();
        return new GmailApiClientFactory(transport).createClient(properties);
    }

    @Bean
    public GmailMimeMessageFactory gmailMimeMessageFactory(
            EmailProperties emailProperties,
            GmailApiProperties gmailApiProperties) {
        return new GmailMimeMessageFactory(emailProperties, gmailApiProperties);
    }

    @Bean
    @DependsOn("emailConfiguration")
    public GmailApiEmailAdapter gmailApiEmailAdapter(
            GmailMimeMessageFactory mimeMessageFactory,
            GmailMessageDispatcher messageDispatcher) {
        return new GmailApiEmailAdapter(mimeMessageFactory, messageDispatcher);
    }
}
