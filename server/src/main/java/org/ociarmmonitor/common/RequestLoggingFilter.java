package org.ociarmmonitor.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

  public static final String REQUEST_ID_HEADER = "X-Request-Id";
  public static final String MDC_REQUEST_ID = "requestId";

  private static final Logger LOGGER = LoggerFactory.getLogger(RequestLoggingFilter.class);
  private static final int MAX_REQUEST_ID_LENGTH = 64;

  @Override
  protected void doFilterInternal(
    HttpServletRequest request,
    HttpServletResponse response,
    FilterChain filterChain
  ) throws ServletException, IOException {
    String requestId = resolveRequestId(request);
    long startTime = System.currentTimeMillis();
    MDC.put(MDC_REQUEST_ID, requestId);
    response.setHeader(REQUEST_ID_HEADER, requestId);

    try {
      filterChain.doFilter(request, response);
    } finally {
      long durationMillis = System.currentTimeMillis() - startTime;
      if (isPublicReportRequest(request)) {
        LOGGER.info(
          "HTTP {} {} status={} durationMs={}",
          request.getMethod(),
          request.getRequestURI(),
          response.getStatus(),
          durationMillis
        );
      } else {
        LOGGER.info(
          "HTTP {} {} status={} durationMs={} clientIp={}",
          request.getMethod(),
          request.getRequestURI(),
          response.getStatus(),
          durationMillis,
          clientIp(request)
        );
      }
      MDC.remove(MDC_REQUEST_ID);
    }
  }

  private boolean isPublicReportRequest(HttpServletRequest request) {
    return request.getRequestURI().startsWith(request.getContextPath() + "/public/reports/");
  }

  private String resolveRequestId(HttpServletRequest request) {
    String incomingRequestId = request.getHeader(REQUEST_ID_HEADER);
    if (incomingRequestId == null || incomingRequestId.isBlank()) {
      return UUID.randomUUID().toString();
    }
    return sanitizeRequestId(incomingRequestId);
  }

  private String sanitizeRequestId(String value) {
    String sanitized = value.trim().replaceAll("[^A-Za-z0-9._:-]", "_");
    if (sanitized.length() <= MAX_REQUEST_ID_LENGTH) {
      return sanitized;
    }
    return sanitized.substring(0, MAX_REQUEST_ID_LENGTH);
  }

  private String clientIp(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return forwardedFor.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
