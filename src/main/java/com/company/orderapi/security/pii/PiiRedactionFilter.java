package com.company.orderapi.security.pii;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * PR #27 - PII redaction for LOGS (GDPR: personal data must not end up in
 * infrastructure where only an ops audience can read it).
 *
 * <p>The filter buffers the request/response of every {@code /api/**} call and
 * emits a DEBUG log line whose bodies are passed through {@link PiiMasker} -
 * so support can debug with bodies while e-mails/phones/names stay masked in
 * the log files. Raw values are never logged here, even for privileged callers:
 * log redaction is a property of the *log sink*, not of the caller's rights.
 *
 * <p>Logging is DEBUG-gated (default profile) and fully silent in the prod
 * profile (INFO), mirroring the PR #15 SQL-logging decision.
 */
@Component
public class PiiRedactionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PiiRedactionFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper wrappedRequest =
                new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse =
                new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            if (log.isDebugEnabled()) {
                log.debug("http {} {} -> {} body={}",
                        request.getMethod(), uri, wrappedResponse.getStatus(),
                        redacted(wrappedResponse.getContentAsByteArray()));
                byte[] requestBody = wrappedRequest.getContentAsByteArray();
                if (requestBody.length > 0) {
                    log.debug("http {} {} request body (redacted)={}",
                            request.getMethod(), uri, redacted(requestBody));
                }
            }
            // The wrapper buffered the body; write it to the real response.
            wrappedResponse.copyBodyToResponse();
        }
    }

    private String redacted(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        return PiiMasker.redactJson(new String(body, StandardCharsets.UTF_8));
    }
}
