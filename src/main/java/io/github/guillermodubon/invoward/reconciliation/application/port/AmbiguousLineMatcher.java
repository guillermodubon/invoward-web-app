package io.github.guillermodubon.invoward.reconciliation.application.port;

import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousLineMatchingInput;
import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousMatchSuggestion;

import java.util.Optional;

/** Optional provider-neutral suggestion port for genuinely ambiguous fuzzy candidates. */
public interface AmbiguousLineMatcher {

    /** Returns empty when no suggestion is available; a returned key still requires application validation. */
    Optional<AmbiguousMatchSuggestion> suggest(AmbiguousLineMatchingInput input);
}
