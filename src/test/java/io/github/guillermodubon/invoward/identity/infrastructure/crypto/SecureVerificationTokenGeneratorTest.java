package io.github.guillermodubon.invoward.identity.infrastructure.crypto;

import io.github.guillermodubon.invoward.identity.application.model.GeneratedVerificationToken;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureVerificationTokenGeneratorTest {

    @Test
    void generatesUrlSafeUnpaddedTokenFromThirtyTwoRandomBytes() {
        byte[] randomBytes = new byte[32];
        for (int index = 0; index < randomBytes.length; index++) {
            randomBytes[index] = (byte) index;
        }
        RecordingSecureRandom secureRandom = new RecordingSecureRandom(randomBytes);

        SecureVerificationTokenGenerator generator = new SecureVerificationTokenGenerator(secureRandom);
        GeneratedVerificationToken generated = generator.generate();

        assertEquals(32, secureRandom.requestedByteCount);
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes), generated.rawToken());
        assertTrue(generated.rawToken().matches("[A-Za-z0-9_-]{43}"));
        assertTrue(!generated.rawToken().contains("="));
        assertEquals(generator.hash(generated.rawToken()), generated.tokenHash());
        assertNotEquals(generated.rawToken(), generated.tokenHash());
    }

    @Test
    void hashesFixedRawTokenDeterministicallyAsLowercaseSha256Hex() {
        SecureVerificationTokenGenerator generator = new SecureVerificationTokenGenerator();
        String firstHash = generator.hash("abc");
        String secondHash = generator.hash("abc");

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", firstHash);
        assertEquals(firstHash, secondHash);
        assertTrue(firstHash.matches("[0-9a-f]{64}"));
    }

    @Test
    void generatedTokensDifferAcrossIndependentSecureRandomCalls() {
        SecureVerificationTokenGenerator generator = new SecureVerificationTokenGenerator();

        assertNotEquals(generator.generate().rawToken(), generator.generate().rawToken());
    }

    @Test
    void stringRepresentationRedactsRawTokenAndHash() {
        GeneratedVerificationToken generated = new SecureVerificationTokenGenerator().generate();

        assertTrue(!generated.toString().contains(generated.rawToken()));
        assertTrue(!generated.toString().contains(generated.tokenHash()));
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
