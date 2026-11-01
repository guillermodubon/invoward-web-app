package io.github.guillermodubon.invoward.insights.application.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/** A monetary aggregate for one persisted currency, never a cross-currency total. */
public record CurrencyAmount(String currency, BigDecimal amount, long contributingAnalyses) {

    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

    public CurrencyAmount {
        Objects.requireNonNull(currency, "currency is required");
        Objects.requireNonNull(amount, "amount is required");
        if (!CURRENCY_CODE.matcher(currency).matches()) {
            throw new IllegalArgumentException("Currency must be a three-letter uppercase code.");
        }
        if (contributingAnalyses < 0) {
            throw new IllegalArgumentException("Contributing analysis count cannot be negative.");
        }
    }
}
