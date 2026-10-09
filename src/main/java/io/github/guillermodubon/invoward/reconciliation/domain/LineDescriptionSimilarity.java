package io.github.guillermodubon.invoward.reconciliation.domain;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Deterministic Sørensen-Dice similarity over already-normalized descriptions. */
public final class LineDescriptionSimilarity {

    private static final double TOKEN_WEIGHT = 0.60;
    private static final double TRIGRAM_WEIGHT = 0.40;
    private static final int TRIGRAM_CODE_POINTS = 3;

    public Features precompute(String normalizedDescription) {
        Objects.requireNonNull(normalizedDescription, "normalizedDescription must not be null");
        return new Features(tokens(normalizedDescription), characterTrigrams(normalizedDescription));
    }

    public double score(String firstNormalizedDescription, String secondNormalizedDescription) {
        return score(precompute(firstNormalizedDescription), precompute(secondNormalizedDescription));
    }

    public double score(Features first, Features second) {
        Objects.requireNonNull(first, "first features must not be null");
        Objects.requireNonNull(second, "second features must not be null");
        double tokenDice = dice(first.tokens(), second.tokens());
        if (first.characterTrigrams().isEmpty() || second.characterTrigrams().isEmpty()) {
            return tokenDice;
        }
        double trigramDice = dice(first.characterTrigrams(), second.characterTrigrams());
        return TOKEN_WEIGHT * tokenDice + TRIGRAM_WEIGHT * trigramDice;
    }

    static double dice(Set<String> first, Set<String> second) {
        if (first.isEmpty() || second.isEmpty()) {
            return 0.0;
        }
        Set<String> smaller = first.size() <= second.size() ? first : second;
        Set<String> larger = smaller == first ? second : first;
        long intersection = smaller.stream().filter(larger::contains).count();
        return (2.0 * intersection) / (first.size() + second.size());
    }

    private static Set<String> tokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder token = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)) {
                token.appendCodePoint(codePoint);
            } else {
                addToken(tokens, token);
            }
        });
        addToken(tokens, token);
        return Set.copyOf(tokens);
    }

    private static void addToken(Set<String> tokens, StringBuilder token) {
        if (!token.isEmpty()) {
            tokens.add(token.toString());
            token.setLength(0);
        }
    }

    private static Set<String> characterTrigrams(String text) {
        int[] codePoints = text.codePoints().toArray();
        if (codePoints.length < TRIGRAM_CODE_POINTS) {
            return Set.of();
        }
        Set<String> trigrams = new HashSet<>();
        for (int start = 0; start <= codePoints.length - TRIGRAM_CODE_POINTS; start++) {
            trigrams.add(new String(codePoints, start, TRIGRAM_CODE_POINTS));
        }
        return Set.copyOf(trigrams);
    }

    public record Features(Set<String> tokens, Set<String> characterTrigrams) {

        public Features {
            tokens = Set.copyOf(tokens);
            characterTrigrams = Set.copyOf(characterTrigrams);
        }
    }
}
