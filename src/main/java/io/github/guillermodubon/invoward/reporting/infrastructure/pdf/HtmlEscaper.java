package io.github.guillermodubon.invoward.reporting.infrastructure.pdf;

/** Escapes untrusted values for use as XHTML text. */
public final class HtmlEscaper {

    private HtmlEscaper() {
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder escaped = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            switch (codePoint) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> appendXmlCharacter(escaped, codePoint);
            }
        }
        return escaped.toString();
    }

    private static void appendXmlCharacter(StringBuilder escaped, int codePoint) {
        if (isValidXmlCharacter(codePoint)) {
            escaped.appendCodePoint(codePoint);
        } else {
            escaped.append('\uFFFD');
        }
    }

    private static boolean isValidXmlCharacter(int codePoint) {
        return codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
                || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }
}
