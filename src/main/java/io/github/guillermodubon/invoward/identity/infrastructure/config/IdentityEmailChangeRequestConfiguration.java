package io.github.guillermodubon.invoward.identity.infrastructure.config;

import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.PasswordHasher;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.identity.application.service.RequestEmailChangeService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class IdentityEmailChangeRequestConfiguration {

    @Bean
    public RequestEmailChangeService requestEmailChangeService(
            UserAccountRepository userAccountRepository,
            EmailVerificationTokenRepository tokenRepository,
            PasswordHasher passwordHasher,
            VerificationTokenGenerator tokenGenerator,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            IdentityProperties identityProperties) {
        return new RequestEmailChangeService(
                userAccountRepository,
                tokenRepository,
                passwordHasher,
                tokenGenerator,
                eventPublisher,
                clock,
                identityProperties.emailChangeTokenTtl(),
                identityProperties.emailChangeRequestCooldown());
    }
}
