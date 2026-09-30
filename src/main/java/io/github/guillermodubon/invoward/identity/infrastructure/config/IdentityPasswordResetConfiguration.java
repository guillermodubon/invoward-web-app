package io.github.guillermodubon.invoward.identity.infrastructure.config;

import io.github.guillermodubon.invoward.identity.application.port.PasswordResetTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.PasswordHasher;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserSessionInvalidator;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.identity.application.service.ForgotPasswordService;
import io.github.guillermodubon.invoward.identity.application.service.ResetPasswordService;
import io.github.guillermodubon.invoward.identity.application.service.ResetPasswordTransaction;
import io.github.guillermodubon.invoward.identity.application.service.PasswordChangedEmailFactory;
import io.github.guillermodubon.invoward.identity.application.service.PasswordChangedListener;
import io.github.guillermodubon.invoward.notification.application.port.EmailSender;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class IdentityPasswordResetConfiguration {

    @Bean
    public ForgotPasswordService forgotPasswordService(
            UserAccountRepository userAccountRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            VerificationTokenGenerator verificationTokenGenerator,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            IdentityProperties identityProperties) {
        return new ForgotPasswordService(
                userAccountRepository,
                passwordResetTokenRepository,
                verificationTokenGenerator,
                eventPublisher,
                clock,
                identityProperties.passwordResetTokenTtl(),
                identityProperties.passwordResetRequestCooldown());
    }

    @Bean
    public ResetPasswordTransaction resetPasswordTransaction(
            PasswordResetTokenRepository passwordResetTokenRepository,
            UserAccountRepository userAccountRepository,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        return new ResetPasswordTransaction(
                passwordResetTokenRepository, userAccountRepository, eventPublisher, clock);
    }

    @Bean
    public ResetPasswordService resetPasswordService(
            PasswordResetTokenRepository passwordResetTokenRepository,
            UserAccountRepository userAccountRepository,
            VerificationTokenGenerator verificationTokenGenerator,
            PasswordHasher passwordHasher,
            ResetPasswordTransaction resetPasswordTransaction,
            Clock clock) {
        return new ResetPasswordService(
                passwordResetTokenRepository, userAccountRepository, verificationTokenGenerator,
                passwordHasher, resetPasswordTransaction, clock);
    }

    @Bean
    public PasswordChangedEmailFactory passwordChangedEmailFactory() {
        return new PasswordChangedEmailFactory();
    }

    @Bean
    public PasswordChangedListener passwordChangedListener(
            UserSessionInvalidator userSessionInvalidator,
            PasswordChangedEmailFactory emailFactory,
            EmailSender emailSender) {
        return new PasswordChangedListener(userSessionInvalidator, emailFactory, emailSender);
    }
}
