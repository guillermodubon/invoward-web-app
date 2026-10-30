package io.github.guillermodubon.invoward.sharing.infrastructure.token;

import io.github.guillermodubon.invoward.sharing.application.model.GeneratedShareToken;
import io.github.guillermodubon.invoward.sharing.application.port.ShareTokenGenerator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/** Generates 256-bit bearer tokens and hashes validated tokens for persistence lookup. */
@Component
public final class SecureRandomShareTokenGenerator implements ShareTokenGenerator {

    private static final int TOKEN_BYTE_LENGTH = 32;
    private final SecureRandom secureRandom;

    public SecureRandomShareTokenGenerator() {
        this(new SecureRandom());
    }

    SecureRandomShareTokenGenerator(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    @Override
    public GeneratedShareToken generate() {
        byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(randomBytes);
        try {
            String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
            return new GeneratedShareToken(rawToken, hash(rawToken));
        } finally {
            Arrays.fill(randomBytes, (byte) 0);
        }
    }

    @Override
    public String hash(String rawToken) {
        if (!GeneratedShareToken.isCanonicalRawToken(rawToken)) {
            throw new IllegalArgumentException("rawToken is malformed");
        }
        byte[] rawTokenBytes = rawToken.getBytes(StandardCharsets.UTF_8);
        byte[] digest = null;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(rawTokenBytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        } finally {
            Arrays.fill(rawTokenBytes, (byte) 0);
            if (digest != null) {
                Arrays.fill(digest, (byte) 0);
            }
        }
    }
}
