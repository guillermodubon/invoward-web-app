package io.github.guillermodubon.invoward.identity.infrastructure.security;

import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.identity.infrastructure.config.IdentityPasswordConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaoAuthenticationProviderConfigurationTest {

    private static final String EMAIL = "person@example.com";
    private static final String PASSWORD = "a sufficiently long authentication passphrase";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void daoProviderUsesTheConfiguredArgon2EncoderToMatchPasswords() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());

            DaoAuthenticationProvider provider = context.getBean(DaoAuthenticationProvider.class);
            Authentication authentication = provider.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(EMAIL, PASSWORD));

            assertTrue(authentication.isAuthenticated());
            assertEquals(EMAIL, authentication.getName());
            assertTrue(((AuthenticatedUserPrincipal) authentication.getPrincipal()).getAuthorities().isEmpty());
            assertThrows(BadCredentialsException.class, () -> provider.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(EMAIL, "incorrect passphrase")));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({IdentityPasswordConfiguration.class, DaoAuthenticationProviderConfiguration.class})
    static class TestConfiguration {

        @Bean
        UserDetailsService testUserDetailsService(PasswordEncoder passwordEncoder) {
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            UserAccount account = new UserAccount(
                    UUID.fromString("c12fdd37-107b-4f13-a9bc-d30778f54293"),
                    "Person",
                    EMAIL,
                    passwordEncoder.encode(PASSWORD),
                    true,
                    UserStatus.ACTIVE,
                    0,
                    now,
                    now);
            AuthenticatedUserPrincipal principal = new AuthenticatedUserPrincipal(account);
            return username -> {
                if (EMAIL.equals(username)) {
                    return principal;
                }
                throw new UsernameNotFoundException("User not found");
            };
        }
    }
}
