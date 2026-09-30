package io.github.guillermodubon.invoward.identity.infrastructure.crypto;

import io.github.guillermodubon.invoward.identity.application.port.PasswordHasher;
import io.github.guillermodubon.invoward.identity.infrastructure.config.IdentityPasswordConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringSecurityPasswordHasherTest {

    private static final String RAW_PASSWORD = "a sufficiently long passphrase for the test";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(IdentityPasswordConfiguration.class);

    @Test
    void usesArgon2idWithApprovedParametersAndVerifiesPasswords() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(PasswordEncoder.class).size());
            assertEquals(1, context.getBeansOfType(PasswordHasher.class).size());

            PasswordEncoder passwordEncoder = context.getBean(PasswordEncoder.class);
            assertInstanceOf(Argon2PasswordEncoder.class, passwordEncoder);

            PasswordHasher passwordHasher = context.getBean(PasswordHasher.class);
            assertInstanceOf(SpringSecurityPasswordHasher.class, passwordHasher);
            String encodedPassword = passwordHasher.hash(RAW_PASSWORD);

            assertTrue(encodedPassword.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"));
            assertFalse(encodedPassword.contains(RAW_PASSWORD));
            assertTrue(passwordHasher.matches(RAW_PASSWORD, encodedPassword));
            assertFalse(passwordHasher.matches("a different passphrase", encodedPassword));
            assertTrue(passwordEncoder.matches(RAW_PASSWORD, encodedPassword));
            assertFalse(passwordEncoder.matches("a different passphrase", encodedPassword));

            String[] encodedParts = encodedPassword.split("\\$");
            assertEquals(6, encodedParts.length);
            assertEquals(16, Base64.getDecoder().decode(encodedParts[4]).length);
            assertEquals(32, Base64.getDecoder().decode(encodedParts[5]).length);

            assertNotEquals(encodedPassword, passwordHasher.hash(RAW_PASSWORD));
        });
    }
}
