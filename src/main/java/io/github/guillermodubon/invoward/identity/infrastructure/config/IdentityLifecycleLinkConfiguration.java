package io.github.guillermodubon.invoward.identity.infrastructure.config;

import io.github.guillermodubon.invoward.identity.application.port.EmailChangeConfirmationLinkBuilder;
import io.github.guillermodubon.invoward.identity.application.port.PasswordResetLinkBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IdentityLifecycleLinkConfiguration {

    @Bean
    public PasswordResetLinkBuilder passwordResetLinkBuilder(IdentityProperties identityProperties) {
        return identityProperties::passwordResetUrlWithToken;
    }

    @Bean
    public EmailChangeConfirmationLinkBuilder emailChangeConfirmationLinkBuilder(
            IdentityProperties identityProperties) {
        return identityProperties::emailChangeConfirmationUrlWithToken;
    }
}
