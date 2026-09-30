package io.github.guillermodubon.invoward.identity.infrastructure.config;

import io.github.guillermodubon.invoward.identity.application.port.PasswordResetTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.PasswordHasher;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.service.ChangePasswordService;
import io.github.guillermodubon.invoward.identity.application.service.ChangePasswordTransaction;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class IdentityPasswordChangeConfiguration {

    @Bean
    public ChangePasswordTransaction changePasswordTransaction(
            UserAccountRepository userAccountRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        return new ChangePasswordTransaction(
                userAccountRepository, passwordResetTokenRepository, eventPublisher, clock);
    }

    @Bean
    public ChangePasswordService changePasswordService(
            UserAccountRepository userAccountRepository,
            PasswordHasher passwordHasher,
            ChangePasswordTransaction changePasswordTransaction) {
        return new ChangePasswordService(userAccountRepository, passwordHasher, changePasswordTransaction);
    }
}
