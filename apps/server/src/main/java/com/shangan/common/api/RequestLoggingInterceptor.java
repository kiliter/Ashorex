package com.shangan.common.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 在 Spring Security 完成认证后记录请求结果，确保用户 ID、模块、耗时和 Request ID 可关联。 */
@Component
public class RequestLoggingInterceptor implements HandlerInterceptor {
  private static final Logger log = LoggerFactory.getLogger(RequestLoggingInterceptor.class);
  private static final String STARTED_AT = RequestLoggingInterceptor.class.getName() + ".startedAt";

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    request.setAttribute(STARTED_AT, System.nanoTime());
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request,
      HttpServletResponse response,
      Object handler,
      Exception exception) {
    Object started = request.getAttribute(STARTED_AT);
    long durationMs = started instanceof Long value ? (System.nanoTime() - value) / 1_000_000 : 0;
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    String userId =
        authentication == null || !authentication.isAuthenticated()
            ? "anonymous"
            : authentication.getName();
    log.info(
        "HTTP 请求完成 method={} path={} module={} status={} durationMs={} userId={}",
        request.getMethod(),
        safePath(request.getRequestURI()),
        module(request.getRequestURI()),
        response.getStatus(),
        durationMs,
        userId);
  }

  private String safePath(String path) {
    return path.startsWith("/api/v1/playback/") ? "/api/v1/playback/[REDACTED]" : path;
  }

  private String module(String path) {
    String[] segments = path.split("/");
    if (segments.length > 3 && "api".equals(segments[1])) return segments[3];
    if (segments.length > 2 && "admin".equals(segments[1])) return "admin";
    if (segments.length > 2 && "actuator".equals(segments[1])) return "actuator";
    return "web";
  }
}
