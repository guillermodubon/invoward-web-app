package io.github.guillermodubon.invoward.reconciliation.infrastructure.ai.disabled;

import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousLineMatchingInput;
import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousMatchSuggestion;
import io.github.guillermodubon.invoward.reconciliation.application.port.AmbiguousLineMatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Safe no-provider implementation; deterministic matching and human review remain available. */
@Component
@ConditionalOnProperty(prefix = "spring.ai.model", name = "chat", havingValue = "none", matchIfMissing = true)
public final class DisabledAmbiguousLineMatcher implements AmbiguousLineMatcher {

    @Override
    public Optional<AmbiguousMatchSuggestion> suggest(AmbiguousLineMatchingInput input) {
        return Optional.empty();
    }
}
