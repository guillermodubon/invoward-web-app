package io.github.guillermodubon.invoward.reconciliation.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineDescriptionSimilarityTest {

    private final LineDescriptionSimilarity similarity = new LineDescriptionSimilarity();

    @Test
    void usesUnicodeLettersAndDigitsAsTokensAndOtherCharactersAsSeparators() {
        assertEquals(Set.of("café", "2026"), similarity.precompute("café, 2026").tokens());
        assertEquals(Set.of("abc", "def"), similarity.precompute("abc/def").tokens());
    }

    @Test
    void calculatesTokenAndCharacterTrigramDiceScores() {
        assertEquals(0.5, LineDescriptionSimilarity.dice(
                Set.of("invoice", "paper"), Set.of("paper", "clip")), 0.000001);
        assertEquals(0.5, LineDescriptionSimilarity.dice(
                Set.of("abc", "bcd"), Set.of("abc", "bce")), 0.000001);
        assertEquals(1.0, similarity.score("red paper clips", "red paper clips"), 0.000001);
    }

    @Test
    void keepsShortDescriptionsOnTokenDiceAndHandlesTokenReordering() {
        assertEquals(1.0, similarity.score("ab", "ab"), 0.000001);
        assertTrue(similarity.precompute("ab").characterTrigrams().isEmpty());
        assertTrue(similarity.score("bolt nut", "nut bolt") >= 0.60);
    }

    @Test
    void returnsBoundedDeterministicScoresForSimilarAndUnrelatedDescriptions() {
        double close = similarity.score("stainless steel bolt", "stainless steel bolts");
        double unrelated = similarity.score("stainless steel bolt", "annual service subscription");

        assertTrue(close > 0.0 && close <= 1.0);
        assertTrue(unrelated >= 0.0 && unrelated < close);
        assertEquals(close, similarity.score("stainless steel bolt", "stainless steel bolts"), 0.0);
    }
}
