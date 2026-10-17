package io.github.guillermodubon.invoward.reporting.infrastructure.pdf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HtmlEscaperTest {

    @Test
    void escapesMarkupDelimitersQuotesAndAmpersands() {
        assertEquals("&lt;script&gt;&amp;&quot;&#39;", HtmlEscaper.escape("<script>&\"'"));
    }

    @Test
    void replacesCharactersNotAllowedInXmlText() {
        assertEquals("safe\uFFFDtext", HtmlEscaper.escape("safe\u0000text"));
    }

    @Test
    void treatsNullAsAnEmptyTextValue() {
        assertEquals("", HtmlEscaper.escape(null));
    }
}
