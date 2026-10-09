package io.github.guillermodubon.invoward.support.ai;

import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousLineMatchingInput;
import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousMatchSuggestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeAmbiguousLineMatcherTest {

    private final AmbiguousLineMatchingInput input = new AmbiguousLineMatchingInput(
            new AmbiguousLineMatchingInput.Line("REF", "R-1", "Reference product", "each"),
            List.of(new AmbiguousLineMatchingInput.Line("C1", "I-1", "Invoice product", "each"),
                    new AmbiguousLineMatchingInput.Line("C2", null, "Alternative invoice product", null)));

    @Test
    void supportsCandidateNoMatchUnavailableInvalidResponseInputInspectionAndReset() {
        FakeAmbiguousLineMatcher fake = new FakeAmbiguousLineMatcher();

        assertTrue(fake.suggest(input).isEmpty());
        fake.configureCandidate("C2");
        assertEquals("C2", fake.suggest(input).orElseThrow().candidateKey());

        fake.configureNoMatch();
        AmbiguousMatchSuggestion noMatch = fake.suggest(input).orElseThrow();
        assertTrue(noMatch.isNoMatch());
        assertEquals(AmbiguousMatchSuggestion.NO_MATCH, noMatch.candidateKey());

        fake.configureUnavailable();
        assertTrue(fake.suggest(input).isEmpty());

        fake.configureInvalidResponse();
        AmbiguousMatchSuggestion invalid = fake.suggest(input).orElseThrow();
        assertFalse(input.invoiceCandidates().stream().anyMatch(candidate -> candidate.label()
                .equals(invalid.candidateKey())));

        assertEquals(5, fake.callCount());
        assertEquals(5, fake.inputs().size());
        assertEquals(input, fake.lastInput().orElseThrow());

        fake.reset();
        assertTrue(fake.suggest(input).isEmpty());
        assertEquals(1, fake.callCount());
        assertEquals(List.of(input), fake.inputs());
    }
}
