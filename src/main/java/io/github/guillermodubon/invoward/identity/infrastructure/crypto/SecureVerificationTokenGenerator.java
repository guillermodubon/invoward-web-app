package io.github.guillermodubon.invoward.identity.infrastructure.crypto;

import io.github.guillermodubon.invoward.identity.application.model.GeneratedVerificationToken;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/** Creates 256-bit tokens and computes their SHA-256 hashes. */
@Component
public final class SecureVerificationTokenGenerator implements VerificationTokenGenerator {

    private static final int TOKEN_BYTE_LENGTH = 32;
    private final SecureRandom secureRandom;

    public SecureVerificationTokenGenerator() {
        this(new SecureRandom());
    }

    SecureVerificationTokenGenerator(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    @Override
    public GeneratedVerificationToken generate() {
        byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(randomBytes);
        try {
            String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
            return new GeneratedVerificationToken(rawToken, hash(rawToken));
        } finally {
            Arrays.fill(randomBytes, (byte) 0);
        }
    }

    @Override
    public String hash(String rawToken) {
        return hashToken(rawToken);
    }

    private static String hashToken(String rawToken) {
        Objects.requireNonNull(rawToken, "rawToken must not be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
