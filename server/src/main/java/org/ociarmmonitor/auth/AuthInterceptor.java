package org.ociarmmonitor.auth;

import org.ociarmmonitor.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

  private final ObjectMapper objectMapper;

  public AuthInterceptor(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
    if (
      "OPTIONS".equalsIgnoreCase(request.getMethod())
        || request.getRequestURI().endsWith("/auth/login")
        || isPublicReportRequest(request)
    ) {
      return true;
    }
    HttpSession session = request.getSession(false);
    if (session != null && session.getAttribute(AuthService.SESSION_USER_KEY) instanceof String) {
      return true;
    }
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail("未登录或登录已过期")));
    return false;
  }

  private boolean isPublicReportRequest(HttpServletRequest request) {
    String requestUri = request.getRequestURI();
    String contextPath = request.getContextPath();
    String path = contextPath == null || contextPath.isBlank()
      ? requestUri
      : requestUri.substring(Math.min(contextPath.length(), requestUri.length()));
    return path.startsWith("/public/reports/");
  }
}
