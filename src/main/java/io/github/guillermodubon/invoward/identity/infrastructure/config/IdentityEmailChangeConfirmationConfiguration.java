package io.github.guillermodubon.invoward.identity.infrastructure.config;

import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserSessionInvalidator;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.identity.application.service.ConfirmEmailChangeService;
import io.github.guillermodubon.invoward.identity.application.service.ConfirmEmailChangeTransaction;
import io.github.guillermodubon.invoward.identity.application.service.EmailChangedNotificationListener;
import io.github.guillermodubon.invoward.identity.application.service.EmailChangedNotificationFactory;
import io.github.guillermodubon.invoward.notification.application.port.EmailSender;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class IdentityEmailChangeConfirmationConfiguration {

    @Bean
    public ConfirmEmailChangeTransaction confirmEmailChangeTransaction(
            EmailVerificationTokenRepository tokenRepository,
            UserAccountRepository userRepository,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        return new ConfirmEmailChangeTransaction(tokenRepository, userRepository, eventPublisher, clock);
    }

    @Bean
    public ConfirmEmailChangeService confirmEmailChangeService(
            VerificationTokenGenerator tokenGenerator,
            ConfirmEmailChangeTransaction transaction) {
        return new ConfirmEmailChangeService(tokenGenerator, transaction);
    }

    @Bean
    public EmailChangedNotificationFactory emailChangedNotificationFactory() {
        return new EmailChangedNotificationFactory();
    }

    @Bean
    public EmailChangedNotificationListener emailChangedNotificationListener(
            UserSessionInvalidator sessionInvalidator,
            EmailChangedNotificationFactory emailFactory,
            EmailSender emailSender) {
        return new EmailChangedNotificationListener(sessionInvalidator, emailFactory, emailSender);
    }
}
