package io.github.guillermodubon.invoward.reconciliation.infrastructure.ai.disabled;

import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousLineMatchingInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DisabledAmbiguousLineMatcherTest {

    @Test
    void returnsNoSuggestionWithoutFailingDeterministicMatching() {
        DisabledAmbiguousLineMatcher matcher = new DisabledAmbiguousLineMatcher();
        AmbiguousLineMatchingInput input = new AmbiguousLineMatchingInput(
                new AmbiguousLineMatchingInput.Line("REF", "A1", "Widget", "each"),
                List.of(new AmbiguousLineMatchingInput.Line("C1", "A2", "Widget set", "each")));

        assertTrue(matcher.suggest(input).isEmpty());
    }
}
