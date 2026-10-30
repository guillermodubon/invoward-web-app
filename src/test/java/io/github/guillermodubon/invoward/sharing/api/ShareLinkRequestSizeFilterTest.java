package io.github.guillermodubon.invoward.sharing.api;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShareLinkRequestSizeFilterTest {

    private final ShareLinkRequestSizeFilter filter = new ShareLinkRequestSizeFilter();

    @Test
    void rejectsOversizedContentLengthBeforeReadingTheBody() throws ServletException, IOException {
        MockHttpServletRequest request = request(ShareLinkRequestSizeFilter.MAX_REQUEST_BODY_BYTES + 1L);
        request.setContent(new byte[0]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertInvalidRequest(response);
        assertNull(chain.getRequest());
    }

    @Test
    void rejectsOversizedChunkedBodyWithoutContentLength() throws ServletException, IOException {
        MockHttpServletRequest request = request(-1);
        request.setContent(new byte[ShareLinkRequestSizeFilter.MAX_REQUEST_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertInvalidRequest(response);
        assertNull(chain.getRequest());
    }

    @Test
    void forwardsValidBoundedBodyUnchanged() throws ServletException, IOException {
        byte[] body = "{\"expiresIn\":\"7_DAYS\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = request(-1);
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals("", response.getContentAsString());
        assertNotNull(chain.getRequest());
        assertEquals(new String(body, StandardCharsets.UTF_8),
                new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequest request(long contentLength) {
        return new MockHttpServletRequest("POST", "/api/analyses/8d9459ad-67ce-4c27-aa48-a3bc006552b8/share-links") {
            @Override
            public long getContentLengthLong() {
                return contentLength;
            }
        };
    }

    private static void assertInvalidRequest(MockHttpServletResponse response) throws IOException {
        assertEquals(400, response.getStatus());
        assertEquals("application/json", response.getContentType());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("{\"code\":\"INVALID_SHARE_LINK_REQUEST\",\"message\":\"The share-link request is invalid.\"}",
                response.getContentAsString());
    }
}
