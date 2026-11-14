package com.comedown.shared.config;

import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.error.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@link RequestBodyLimit}로 등록한 API의 요청 본문 크기를 제한한다. 넘으면 400 VALIDATION_FAILED.
 * <ul>
 *   <li>Content-Length가 상한을 넘으면 본문을 읽지 않고 바로 거절한다.</li>
 *   <li>길이를 모르는 요청(chunked)은 읽는 도중 상한을 넘는 순간 읽기를 실패시킨다(JSON 해석 실패 → 400).</li>
 * </ul>
 */
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    static final String TOO_LARGE_MESSAGE = "요청 본문이 너무 커요.";

    private final Map<String, Long> limits;
    private final ErrorResponseWriter errorWriter;

    public RequestBodyLimitFilter(List<RequestBodyLimit> limits, ErrorResponseWriter errorWriter) {
        this.limits = limits.stream().collect(Collectors.toUnmodifiableMap(
                RequestBodyLimit::path, RequestBodyLimit::maxBytes, Math::min));
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !limits.containsKey(pathOf(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long max = limits.get(pathOf(request));
        long declared = request.getContentLengthLong();
        if (declared > max) {
            errorWriter.write(response, ErrorCode.VALIDATION_FAILED, TOO_LARGE_MESSAGE);
            return;
        }
        chain.doFilter(new LimitedRequest(request, max), response);
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context.isEmpty() ? uri : uri.substring(context.length());
    }

    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super(TOO_LARGE_MESSAGE);
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long max;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), max);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8
                    : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long max;
        private long count;

        LimitedInputStream(ServletInputStream delegate, long max) {
            this.delegate = delegate;
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                add(n);
            }
            return n;
        }

        private void add(long n) throws IOException {
            count += n;
            if (count > max) {
                throw new BodyTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
