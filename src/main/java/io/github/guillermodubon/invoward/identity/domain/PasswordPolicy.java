package io.github.guillermodubon.invoward.identity.domain;

import java.util.Objects;

/** Passphrase-friendly password constraints for identity operations. */
public final class PasswordPolicy {

    public static final int MIN_CODE_POINTS = 15;
    public static final int MAX_CODE_POINTS = 128;

    private PasswordPolicy() {
    }

    /** Validates without trimming or otherwise changing the supplied password. */
    public static void validate(String rawPassword) {
        Objects.requireNonNull(rawPassword, "rawPassword must not be null");

        int codePointCount = rawPassword.codePointCount(0, rawPassword.length());
        if (codePointCount < MIN_CODE_POINTS || codePointCount > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("Password must contain between 15 and 128 Unicode code points");
        }

        boolean containsNonWhitespace = rawPassword.codePoints()
                .anyMatch(codePoint -> !UnicodeWhitespace.isWhitespace(codePoint));
        if (!containsNonWhitespace) {
            throw new IllegalArgumentException("Password must contain at least one non-whitespace character");
        }
    }
}
