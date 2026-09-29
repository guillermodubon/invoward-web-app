package io.github.guillermodubon.invoward.identity.infrastructure.config;

import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.RegistrationVerificationLinkBuilder;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.identity.application.service.RegisterUserTransaction;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class IdentityRegistrationConfiguration {

    @Bean
    public RegistrationVerificationLinkBuilder registrationVerificationLinkBuilder(
            IdentityProperties identityProperties) {
        return identityProperties::verificationUrlWithToken;
    }

    @Bean
    public RegisterUserTransaction registerUserTransaction(
            UserAccountRepository userAccountRepository,
            EmailVerificationTokenRepository verificationTokenRepository,
            VerificationTokenGenerator verificationTokenGenerator,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            IdentityProperties identityProperties) {
        return new RegisterUserTransaction(
                userAccountRepository,
                verificationTokenRepository,
                verificationTokenGenerator,
                eventPublisher,
                clock,
                identityProperties.registrationVerificationTokenTtl());
    }
}
