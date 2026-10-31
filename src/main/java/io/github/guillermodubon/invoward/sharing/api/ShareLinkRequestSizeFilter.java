package io.github.guillermodubon.invoward.sharing.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

/** Bounds share-link JSON input before Jackson parses it, including chunked requests. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public final class ShareLinkRequestSizeFilter extends OncePerRequestFilter {

    static final int MAX_REQUEST_BODY_BYTES = 1024;
    private static final int MAX_REQUEST_BODY_BYTES_WITH_SENTINEL = MAX_REQUEST_BODY_BYTES + 1;
    private static final String INVALID_REQUEST_BODY =
            "{\"code\":\"INVALID_SHARE_LINK_REQUEST\",\"message\":\"The share-link request is invalid.\"}";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod())
                || !path.matches("/api/analyses/[^/]+/share-links");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_REQUEST_BODY_BYTES) {
            writeInvalidRequest(response);
            return;
        }

        byte[] body;
        try {
            body = request.getInputStream().readNBytes(MAX_REQUEST_BODY_BYTES_WITH_SENTINEL);
        } catch (IOException exception) {
            writeInvalidRequest(response);
            return;
        }
        if (body.length > MAX_REQUEST_BODY_BYTES) {
            writeInvalidRequest(response);
            return;
        }

        filterChain.doFilter(new BoundedBodyRequest(request, body), response);
    }

    private static void writeInvalidRequest(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.getWriter().write(INVALID_REQUEST_BODY);
    }

    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BoundedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    return input.read(bytes, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new IllegalStateException("Asynchronous request body reading is not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(
                    getInputStream(), encoding == null ? java.nio.charset.StandardCharsets.UTF_8
                            : java.nio.charset.Charset.forName(encoding)));
        }
    }
}
