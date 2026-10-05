package io.github.guillermodubon.invoward.extraction.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

public final class DescriptionNormalizer {

    private DescriptionNormalizer() {
    }

    public static String normalize(String description) {
        Objects.requireNonNull(description, "description must not be null");
        String compatible = Normalizer.normalize(description, Normalizer.Form.NFKC).strip();
        return compatible.replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
