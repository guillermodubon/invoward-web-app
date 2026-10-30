package io.github.guillermodubon.invoward.sharing.application.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.guillermodubon.invoward.sharing.application.port.ShareTokenGenerator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedShareTokenTest {

    private static final String VALID_TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
    private static final String VALID_HASH = "a".repeat(64);

    @Test
    void acceptsCanonicalThirtyTwoByteBase64UrlTokenAndLowercaseSha256Hash() {
        GeneratedShareToken token = new GeneratedShareToken(VALID_TOKEN, VALID_HASH);

        assertTrue(GeneratedShareToken.isCanonicalRawToken(token.rawToken()));
    }

    @Test
    void rejectsMalformedTokensWithoutEchoingSensitiveInput() {
        for (String malformed : new String[]{
                "", VALID_TOKEN + "A", VALID_TOKEN.substring(0, 42),
                VALID_TOKEN.substring(0, 42) + "/", VALID_TOKEN.substring(0, 42) + " ",
                VALID_TOKEN.substring(0, 42) + "é", VALID_TOKEN.substring(0, 42) + "B"
        }) {
            assertFalse(GeneratedShareToken.isCanonicalRawToken(malformed));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> new GeneratedShareToken(malformed, VALID_HASH));
            if (!malformed.isEmpty()) {
                assertFalse(error.getMessage().contains(malformed));
            }
        }
    }

    @Test
    void rejectsNonCanonicalBase64UrlLastCharacter() {
        String nonCanonical = VALID_TOKEN.substring(0, 42) + "B";

        assertFalse(GeneratedShareToken.isCanonicalRawToken(nonCanonical));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedShareToken(nonCanonical, VALID_HASH));
    }

    @Test
    void rejectsInvalidAlphabetAtEveryTokenPositionAndBase64PaddingVariants() {
        for (int index = 0; index < VALID_TOKEN.length(); index++) {
            String malformed = VALID_TOKEN.substring(0, index) + "!" + VALID_TOKEN.substring(index + 1);
            assertFalse(GeneratedShareToken.isCanonicalRawToken(malformed));
            assertThrows(IllegalArgumentException.class,
                    () -> new GeneratedShareToken(malformed, VALID_HASH));
        }

        for (String invalidSuffix : new String[]{"=", "+", "/"}) {
            String malformed = VALID_TOKEN.substring(0, 42) + invalidSuffix;
            assertFalse(GeneratedShareToken.isCanonicalRawToken(malformed));
            assertThrows(IllegalArgumentException.class,
                    () -> new GeneratedShareToken(malformed, VALID_HASH));
        }
    }

    @Test
    void rejectsInvalidHashFormat() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratedShareToken(VALID_TOKEN, "A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedShareToken(VALID_TOKEN, "0".repeat(63)));
    }

    @Test
    void stringRepresentationDoesNotExposeTokenOrHash() {
        GeneratedShareToken token = new GeneratedShareToken(VALID_TOKEN, VALID_HASH);

        assertFalse(token.toString().contains(VALID_TOKEN));
        assertFalse(token.toString().contains(VALID_HASH));
    }

    @Test
    void retriesKnownHashCollisionsAtMostTwice() {
        GeneratedShareToken first = token("a", 1);
        GeneratedShareToken second = token("b", 2);
        GeneratedShareToken third = token("c", 3);
        SequenceTokenGenerator generator = new SequenceTokenGenerator(first, second, third);

        GeneratedShareToken selected = generator.generateUnique(hash ->
                hash.equals(first.tokenHash()) || hash.equals(second.tokenHash()));

        assertTrue(selected == third);
        assertTrue(generator.generationCount.get() == ShareTokenGenerator.MAX_GENERATION_ATTEMPTS);
    }

    @Test
    void stopsAfterInitialAttemptAndTwoDuplicateReissues() {
        GeneratedShareToken duplicate = token("d", 4);
        SequenceTokenGenerator generator = new SequenceTokenGenerator(duplicate, duplicate, duplicate);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> generator.generateUnique(ignored -> true));

        assertTrue(generator.generationCount.get() == 3);
        assertFalse(failure.getMessage().contains(duplicate.rawToken()));
        assertFalse(failure.getMessage().contains(duplicate.tokenHash()));
    }

    private static GeneratedShareToken token(String byteValue, int hashDigit) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) byteValue.charAt(0));
        String raw = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new GeneratedShareToken(raw, String.valueOf(hashDigit).repeat(64));
    }

    private static final class SequenceTokenGenerator implements ShareTokenGenerator {

        private final Queue<GeneratedShareToken> tokens = new ArrayDeque<>();
        private final AtomicInteger generationCount = new AtomicInteger();

        private SequenceTokenGenerator(GeneratedShareToken... tokens) {
            this.tokens.addAll(Arrays.asList(tokens));
        }

        @Override
        public GeneratedShareToken generate() {
            generationCount.incrementAndGet();
            return tokens.remove();
        }

        @Override
        public String hash(String rawToken) {
            throw new UnsupportedOperationException("hash is not used in this test");
        }
    }
}
