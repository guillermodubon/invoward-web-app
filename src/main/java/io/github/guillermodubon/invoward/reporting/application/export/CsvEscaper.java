package io.github.guillermodubon.invoward.reporting.application.export;

import java.util.Objects;

/** Sanitizes untrusted text for spreadsheet-safe, RFC 4180-compatible CSV cells. */
public final class CsvEscaper {

    private CsvEscaper() {
    }

    /** Sanitizes and quotes an untrusted text cell. Null values become empty cells. */
    public static String escapeText(String value) {
        if (value == null) {
            return "";
        }

        String sanitized = sanitize(value);
        if (startsWithFormulaMarker(sanitized)) {
            sanitized = "'" + sanitized;
        }
        return quoteIfRequired(sanitized);
    }

    private static String sanitize(String value) {
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (codePoint == '\r' || codePoint == '\n' || codePoint == '\t'
                    || Character.getType(codePoint) == Character.LINE_SEPARATOR
                    || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR) {
                sanitized.append(' ');
            } else if (Character.isISOControl(codePoint) || isUnsafeFormatCharacter(codePoint)) {
                // Remove NUL, unsafe controls, bidi marks and zero-width separators; retain script joiners.
            } else {
                sanitized.appendCodePoint(codePoint);
            }
        }
        return sanitized.toString();
    }

    private static boolean startsWithFormulaMarker(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
                    && Character.getType(codePoint) != Character.FORMAT) {
                return codePoint == '=' || codePoint == '+' || codePoint == '-' || codePoint == '@';
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean isUnsafeFormatCharacter(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.FORMAT && codePoint != '\u200C' && codePoint != '\u200D';
    }

    private static String quoteIfRequired(String value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
