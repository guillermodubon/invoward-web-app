package io.github.guillermodubon.invoward.identity.domain;

/** Unicode whitespace operations shared by identity text policies. */
final class UnicodeWhitespace {

    private UnicodeWhitespace() {
    }

    static boolean isWhitespace(int codePoint) {
        return codePoint == 0x0085
                || Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint);
    }

    static String strip(String value) {
        int start = 0;
        int end = value.length();

        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }

        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }

        return value.substring(start, end);
    }
}
