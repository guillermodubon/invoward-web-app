package io.github.guillermodubon.invoward.sharing.infrastructure.token;

import io.github.guillermodubon.invoward.sharing.application.model.GeneratedShareToken;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureRandomShareTokenGeneratorTest {

    @Test
    void generatesFortyThreeCharacterUrlSafeUnpaddedTokenFromThirtyTwoRandomBytes() {
        byte[] randomBytes = new byte[32];
        for (int index = 0; index < randomBytes.length; index++) {
            randomBytes[index] = (byte) index;
        }
        RecordingSecureRandom secureRandom = new RecordingSecureRandom(randomBytes);
        SecureRandomShareTokenGenerator generator = new SecureRandomShareTokenGenerator(secureRandom);

        GeneratedShareToken token = generator.generate();

        assertEquals(32, secureRandom.requestedByteCount);
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes), token.rawToken());
        assertEquals(43, token.rawToken().length());
        assertTrue(token.rawToken().matches("[A-Za-z0-9_-]{43}"));
        assertFalse(token.rawToken().contains("="));
        assertTrue(token.tokenHash().matches("[0-9a-f]{64}"));
        assertEquals(generator.hash(token.rawToken()), token.tokenHash());
        assertFalse(token.toString().contains(token.rawToken()));
        assertFalse(token.toString().contains(token.tokenHash()));
    }

    @Test
    void computesDeterministicLowercaseSha256OfRawTokenUtf8Bytes() throws Exception {
        SecureRandomShareTokenGenerator generator = new SecureRandomShareTokenGenerator();
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

        String firstHash = generator.hash(token);

        assertTrue(firstHash.matches("[0-9a-f]{64}"));
        assertEquals(firstHash, generator.hash(token));
        assertEquals(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                firstHash);
    }

    @Test
    void independentGenerationCallsProduceDifferentRawTokensAndHashes() {
        SecureRandomShareTokenGenerator generator = new SecureRandomShareTokenGenerator();
        Set<String> rawTokens = new HashSet<>();
        Set<String> tokenHashes = new HashSet<>();

        for (int index = 0; index < 256; index++) {
            GeneratedShareToken generated = generator.generate();
            rawTokens.add(generated.rawToken());
            tokenHashes.add(generated.tokenHash());
        }

        assertEquals(256, rawTokens.size());
        assertEquals(256, tokenHashes.size());
    }

    @Test
    void rejectsMalformedInputBeforeHashingAndDoesNotEchoIt() {
        SecureRandomShareTokenGenerator generator = new SecureRandomShareTokenGenerator();
        String malformed = "x".repeat(42) + "/";

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> generator.hash(malformed));

        assertFalse(exception.getMessage().contains(malformed));
    }

    @Test
    void generatedShareTokenModelDoesNotRepresentPersistenceEntityOrRawTokenStorage() {
        var fields = io.github.guillermodubon.invoward.sharing.infrastructure.persistence.entity.ShareLinkJpaEntity.class
                .getDeclaredFields();

        assertTrue(Arrays.stream(fields).noneMatch(field -> field.getName().equalsIgnoreCase("rawToken")));
        assertTrue(Arrays.stream(fields).anyMatch(field -> field.getName().equals("tokenHash")));
        assertEquals(1, Arrays.stream(fields)
                .filter(field -> field.getName().toLowerCase(java.util.Locale.ROOT).contains("token"))
                .count());
    }

    private static final class RecordingSecureRandom extends SecureRandom {

        private final byte[] bytesToReturn;
        private int requestedByteCount;

        private RecordingSecureRandom(byte[] bytesToReturn) {
            this.bytesToReturn = bytesToReturn.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            requestedByteCount = bytes.length;
            System.arraycopy(bytesToReturn, 0, bytes, 0, bytes.length);
        }
    }
}
